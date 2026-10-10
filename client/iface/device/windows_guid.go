package device

import "os"

// envWintunGUID is an environment fallback for CustomWindowsGUIDString.
//
// It is read once at package initialisation, before any profile config is
// loaded, so a value coming from the profile config (see SetWintunGUID) always
// wins. Supporting it keeps the older behaviour where a custom GUID could only
// be supplied through the environment.
const envWintunGUID = "NB_WINDOWS_GUID"

// CustomWindowsGUIDString is a custom GUID string for the interface
var CustomWindowsGUIDString string

func init() {
	if v := os.Getenv(envWintunGUID); v != "" {
		CustomWindowsGUIDString = v
	}
}

// SetWintunGUID pins the wintun adapter GUID this process creates its interface
// with, so that several instances can run side by side on one Windows host
// instead of all of them claiming the same built-in GUID.
//
// An empty guid is ignored, which keeps the zero value of the profile config
// field equivalent to upstream behaviour: the environment fallback, or failing
// that the built-in default, stays in effect. One process runs one profile, so
// the package-level state this writes is unambiguous.
//
// This file is built on every platform (the variable it sets is only read by
// device_windows.go), so callers need no build tag of their own.
func SetWintunGUID(guid string) {
	if guid == "" {
		return
	}
	CustomWindowsGUIDString = guid
}
