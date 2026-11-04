package internal

import (
	"fmt"
	"net"
)

// probeUDPPort reports whether port can be taken by the interface bind and
// returns the port the system picked when port is 0.
//
// Windows binds a dedicated IPv4 socket and a dedicated IPv6 socket on the same
// number, so one dual-stack probe is not enough: a service that keeps the IPv6
// wildcard with SO_REUSEADDR lets the dual-stack and IPv4 probes succeed while
// the IPv6 socket still fails, and the interface then never comes up.
func probeUDPPort(port int) (int, error) {
	// The dual-stack probe also catches a process holding the port loosely
	// enough to share it with a v4-only or v6-only socket.
	for _, network := range []string{"udp", "udp4", "udp6"} {
		conn, err := net.ListenUDP(network, &net.UDPAddr{Port: port})
		if err != nil {
			return 0, fmt.Errorf("bind %s port %d: %w", network, port, err)
		}

		port = conn.LocalAddr().(*net.UDPAddr).Port
		closeConnWithLog(conn)
	}
	return port, nil
}
