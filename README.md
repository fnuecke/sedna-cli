# Command line runner for Sedna

This serves as a simple command line runner for a Sedna VM and a small reference on how to set up and run a VM from code.

The VirtIO file system providing access to the host filesystem is mounted automatically during boot and an init script mounts things under `/mnt`. Once logged in (as `root`), the host directory is at `/mnt/host_fs`, with `/mnt/host_fs/bin` already on `PATH`.

```shell
ls /mnt/host_fs
```

To terminate a session gracefully, in the guest run

```shell
poweroff
```
