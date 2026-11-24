// JNI entry for the embedded Node runtime.
// Java_ai_lumen_assistant_NodeRuntime_startNode calls node::Start once.

#include <node.h>

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <thread>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {

constexpr char kLogTag[] = "lumen-node";
std::atomic_flag g_started = ATOMIC_FLAG_INIT;

void emit_log(const std::string& line) {
  if (line.empty()) return;
  constexpr size_t kChunk = 900;
  for (size_t i = 0; i < line.size(); i += kChunk) {
    const size_t n = std::min(kChunk, line.size() - i);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%.*s", static_cast<int>(n),
                        line.c_str() + i);
  }
}

void read_log_fd(int fd) {
  std::string pending;
  char buf[1024];
  while (true) {
    const ssize_t n = ::read(fd, buf, sizeof(buf));
    if (n < 0) {
      if (errno == EINTR) continue;
      break;
    }
    if (n == 0) break;
    pending.append(buf, buf + n);
    size_t pos;
    while ((pos = pending.find('\n')) != std::string::npos) {
      std::string line = pending.substr(0, pos);
      if (!line.empty() && line.back() == '\r') line.pop_back();
      emit_log(line);
      pending.erase(0, pos + 1);
    }
    if (pending.size() >= 8192) {
      emit_log(pending);
      pending.clear();
    }
  }
  if (!pending.empty()) emit_log(pending);
}

// Sends stdout and stderr to logcat for the duration of node::Start, then
// restores the previous descriptors so the rest of the process is unchanged.
class StdioToLogcat {
 public:
  StdioToLogcat() {
    if (pipe2(fds_, O_CLOEXEC) != 0) {
      fds_[0] = fds_[1] = -1;
      __android_log_print(ANDROID_LOG_ERROR, kLogTag, "pipe2 failed: %s",
                          ::strerror(errno));
      return;
    }
    reader_ = std::thread(read_log_fd, fds_[0]);
    saved_out_ = ::dup(STDOUT_FILENO);
    saved_err_ = ::dup(STDERR_FILENO);
    if (saved_out_ < 0 || saved_err_ < 0 ||
        ::dup2(fds_[1], STDOUT_FILENO) < 0 ||
        ::dup2(fds_[1], STDERR_FILENO) < 0) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag, "dup2 stdout failed: %s",
                          ::strerror(errno));
      fail_open();
      return;
    }
    ::close(fds_[1]);
    fds_[1] = -1;
    ::setvbuf(stdout, nullptr, _IONBF, 0);
    ::setvbuf(stderr, nullptr, _IONBF, 0);
    active_ = true;
  }

  ~StdioToLogcat() {
    if (active_) {
      ::fflush(stdout);
      ::fflush(stderr);
      if (saved_out_ >= 0) {
        ::dup2(saved_out_, STDOUT_FILENO);
        ::close(saved_out_);
        saved_out_ = -1;
      }
      if (saved_err_ >= 0) {
        ::dup2(saved_err_, STDERR_FILENO);
        ::close(saved_err_);
        saved_err_ = -1;
      }
    } else {
      fail_open();
    }
    if (reader_.joinable()) reader_.join();
    if (fds_[0] >= 0) {
      ::close(fds_[0]);
      fds_[0] = -1;
    }
  }

  StdioToLogcat(const StdioToLogcat&) = delete;
  StdioToLogcat& operator=(const StdioToLogcat&) = delete;

 private:
  void fail_open() {
    if (saved_out_ >= 0) {
      ::dup2(saved_out_, STDOUT_FILENO);
      ::close(saved_out_);
      saved_out_ = -1;
    }
    if (saved_err_ >= 0) {
      ::dup2(saved_err_, STDERR_FILENO);
      ::close(saved_err_);
      saved_err_ = -1;
    }
    if (fds_[1] >= 0) {
      ::close(fds_[1]);
      fds_[1] = -1;
    }
  }

  int fds_[2] = {-1, -1};
  int saved_out_ = -1;
  int saved_err_ = -1;
  bool active_ = false;
  std::thread reader_;
};

// Packs the Java string array into one contiguous byte block and a
// NULL-terminated argv of pointers into that block.
int build_argv(JNIEnv* env, jobjectArray args, std::vector<char>& block,
               std::vector<char*>& argv) {
  if (args == nullptr) return -1;
  const jsize n = env->GetArrayLength(args);
  if (n <= 0) return -1;

  std::vector<std::string> parts;
  parts.reserve(static_cast<size_t>(n));
  size_t bytes = 0;
  for (jsize i = 0; i < n; ++i) {
    jstring js = static_cast<jstring>(env->GetObjectArrayElement(args, i));
    if (js == nullptr) return -1;
    const char* utf = env->GetStringUTFChars(js, nullptr);
    if (utf == nullptr) {
      env->DeleteLocalRef(js);
      return -1;
    }
    parts.emplace_back(utf);
    bytes += parts.back().size() + 1;
    env->ReleaseStringUTFChars(js, utf);
    env->DeleteLocalRef(js);
  }

  block.assign(bytes, '\0');
  argv.assign(static_cast<size_t>(n) + 1, nullptr);
  size_t off = 0;
  for (jsize i = 0; i < n; ++i) {
    argv[static_cast<size_t>(i)] = block.data() + off;
    std::memcpy(argv[static_cast<size_t>(i)], parts[static_cast<size_t>(i)].data(),
                parts[static_cast<size_t>(i)].size() + 1);
    off += parts[static_cast<size_t>(i)].size() + 1;
  }
  argv[static_cast<size_t>(n)] = nullptr;
  return static_cast<int>(n);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ai_lumen_assistant_NodeRuntime_startNode(JNIEnv* env, jclass /*clazz*/,
                                             jobjectArray args) {
  if (g_started.test_and_set(std::memory_order_acq_rel)) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "node::Start already called in this process");
    return -1;
  }

  std::vector<char> block;
  std::vector<char*> argv;
  const int argc = build_argv(env, args, block, argv);
  if (argc < 0) {
    g_started.clear(std::memory_order_release);
    return -1;
  }

  try {
    StdioToLogcat redirect;
    return static_cast<jint>(node::Start(argc, argv.data()));
  } catch (const std::exception& ex) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "node::Start threw: %s",
                        ex.what());
    return -1;
  }
}
