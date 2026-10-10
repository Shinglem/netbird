//go:build !windows

package dns

// SetNRPTKeyPrefix is a no-op off Windows. NRPT is a Windows-only mechanism:
// elsewhere the resolver configuration is not keyed by a prefix this package
// picks, so there is nothing to namespace.
//
// It is defined here so that the profile-config loader can call it on every
// platform without a build tag of its own.
func SetNRPTKeyPrefix(string) {}
