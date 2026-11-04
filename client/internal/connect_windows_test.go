//go:build windows

package internal

import (
	"syscall"
	"testing"
)

// holdIPv6ReusablePort takes the IPv6 wildcard for a port the way a Windows
// system service does: with SO_REUSEADDR, which lets a dual-stack bind succeed
// on top of it while a dedicated IPv6 socket still fails.
func holdIPv6ReusablePort(t *testing.T) int {
	t.Helper()

	fd, err := syscall.Socket(syscall.AF_INET6, syscall.SOCK_DGRAM, syscall.IPPROTO_UDP)
	if err != nil {
		t.Fatalf("create IPv6 socket: %v", err)
	}
	t.Cleanup(func() { _ = syscall.Close(fd) })

	if err := syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1); err != nil {
		t.Fatalf("set SO_REUSEADDR: %v", err)
	}
	if err := syscall.Bind(fd, &syscall.SockaddrInet6{}); err != nil {
		t.Fatalf("bind IPv6 socket: %v", err)
	}

	sa, err := syscall.Getsockname(fd)
	if err != nil {
		t.Fatalf("get socket name: %v", err)
	}
	return sa.(*syscall.SockaddrInet6).Port
}

// The interface opens separate IPv4 and IPv6 sockets on the same WgPort, so a
// port only one family can take is not free: the engine fails to come up and
// then retries the very same port forever.
func TestFreePortSkipsPortHeldOnIPv6(t *testing.T) {
	heldPort := holdIPv6ReusablePort(t)

	got, err := freePort(heldPort)
	if err != nil {
		t.Fatalf("freePort(%d): %v", heldPort, err)
	}
	if got == heldPort {
		t.Fatalf("freePort returned %d, which cannot be bound on IPv6; the interface would never start", heldPort)
	}
}
