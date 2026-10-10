// The server that keeps a copy of the LHOOP app's database. The design is fork/docs/12-server-backup.md.
package main

import (
	"os"

	"lhoop-backup/backup"
)

func main() {
	os.Exit(backup.Main(os.Args[1:], os.Stdout, os.Stderr))
}
