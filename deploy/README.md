# Running the Lumen server

Lumen's server is a small Node.js program plus Tailscale. Pick whichever way of running it suits you.

## Docker (any Linux machine, NAS or mini PC)

```
curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-docker.sh | bash
```

This downloads Lumen to `~/lumen`, starts two containers (Lumen and Tailscale, sharing one network), and prints the
setup address. Settings can also go in `~/lumen/deploy/.env` (see `.env.example`).

## Proxmox

On the Proxmox host, as root:

```
bash -c "$(curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-lxc.sh)"
```

It makes an unprivileged Debian container (2 cores, 2 GB, 8 GB disk), gives it the network device Tailscale uses, and
installs Lumen inside. Override with `CTID=`, `STORAGE=`, `BRIDGE=` before the command.

## Debian or Ubuntu (a VM or a spare computer)

```
curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-debian.sh | sudo bash
```

Lumen runs as the `lumen` system user, keeps its data in `/var/lib/lumen`, and starts at boot (`systemctl status lumen`).

## Finishing setup

Open the address the installer printed, `http://<server>:8080/setup`, from a computer on the same home network:

1. Name your assistant.
2. Choose the AI model and paste its API key. "Save and test" checks it works.
3. Optional: a voice key, for speaking out loud.
4. Sign the server in to Tailscale (the button opens the sign-in). Then, in the
   [Tailscale admin console](https://login.tailscale.com/admin/dns), turn on **MagicDNS** and **HTTPS Certificates**.
5. Install the phone app and sign in to the same Tailscale account. It finds the server.
6. Choose who can use it: everyone on your tailnet (each person gets their own memory) or only listed logins.

After you press Finish, the server stops answering on the home network (apart from a health check). From then on you
reach the settings at `https://<name>.<your-tailnet>.ts.net/setup`, from the owner's Tailscale login.

## Why Tailscale

Your phone needs to reach your server from anywhere, without opening your home network to the internet. Tailscale
makes a private network of just your devices, encrypted end to end, and it is free for personal use. Lumen's app has
it built in, so you don't install the Tailscale app on the phone. Both ends have Tailscale's log upload turned off.

**Running your own coordination server:** if you use [Headscale](https://github.com/juanfont/headscale), set
`TS_EXTRA_ARGS=--login-server=https://your-headscale` in `.env` (Docker), and enter the same address in the app under
"Enter the server address yourself". HTTPS certificates on Headscale depend on its version; plain setups work over HTTP.

## Updating

- Docker: `cd ~/lumen && git pull && cd deploy && docker compose up -d --build`
- Debian / Proxmox: `cd /opt/lumen && git pull && systemctl restart lumen`

The phone app offers updates itself when a new release is out.

## Backups

Everything is in one folder: the `lumen-data` Docker volume, or `/var/lib/lumen`. Back it up, and keep it private:
it holds your memory and your keys (`vault.key` unlocks the stored service keys).

## Removing it

- Docker: `cd ~/lumen/deploy && docker compose down -v` (the `-v` deletes your data)
- Debian: `systemctl disable --now lumen lumen-serve && rm -rf /opt/lumen /var/lib/lumen /etc/systemd/system/lumen*.service`
- Remove the server from your Tailscale admin console.

## Phone calls to businesses (optional, advanced)

Lumen can phone a business for you with a voice agent. That needs a Cartesia account with a phone number, and the
calling service must reach your server's `/calls/hook`, which means exposing that one path with Tailscale Funnel.
Leave it off unless you want it.
