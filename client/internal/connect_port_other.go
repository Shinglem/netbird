//go:build !windows

package internal

import "net"

// probeUDPPort reports whether port can be taken by the interface bind and
// returns the port the system picked when port is 0.
func probeUDPPort(port int) (int, error) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{Port: port})
	if err != nil {
		return 0, err
	}
	defer closeConnWithLog(conn)

	return conn.LocalAddr().(*net.UDPAddr).Port, nil
}
