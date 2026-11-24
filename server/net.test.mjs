import assert from 'node:assert/strict';
const { isPrivateIp } = await import(process.argv[2] || './net.mjs');
for (const ip of ['127.0.0.1', '10.1.2.3', '192.168.1.1', '169.254.169.254', '100.100.1.1', '::1', '::', '::ffff:127.0.0.1', '::ffff:7f00:1', '::ffff:a9fe:a9fe', '::ffff:c0a8:101', '::7f00:1', '::127.0.0.1', '2002:7f00:1::', '2002:c0a8:0101::1', 'fc00::1', 'fd12::1', 'fe80::1', 'ff02::1', '64:ff9b::7f00:1', '2001:db8::1', '2001::1', '[::1]', 'fe80::1%eth0'])
  assert.equal(isPrivateIp(ip), true, ip + ' should be private');
for (const ip of ['8.8.8.8', '1.1.1.1', '::ffff:8.8.8.8', '::ffff:808:808', '2606:4700::1111', '2a00:1450:4001::200e', '2002:808:808::1'])
  assert.equal(isPrivateIp(ip), false, ip + ' should be public');
console.log('net ok');
