# Command line runner for Sedna

This serves as a simple command line runner for a Sedna VM and a small reference on how to set up and run a VM from
code.

To use the VirtIO file system that provides access to the host filesystem, you'll need to load the module and mount it
like so:

```shell
modprobe -a 9pnet_virtio
mkdir -p /tmp/host_fs
mount -t 9p -o trans=virtio,version=9p2000.L host_fs /tmp/host_fs
cd /tmp/host_fs
```

To terminate a session gracefully, inside the VM run
```shell
poweroff
```
