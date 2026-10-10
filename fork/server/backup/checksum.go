package backup

// Hour checksums: how the phone and the server tell that they hold the same rows.
//
// Rows of a sample table are grouped by strap and by hour (ts / 3600). An hour's checksum is a list of
// whole numbers joined by commas: the row count, then for each column in table order, leaving out
// `deviceId`: the sum of an INTEGER column, or the summed length of a TEXT or BLOB column, and after
// that the count of its non-null values when the column may be null. REAL columns add no sum, because a
// sum of decimals depends on the order the rows are read in.
//
// The app has the same rule in Kotlin (fork/app/backup/BackupChecksum.kt). Both are tested against
// testdata/checksum_cases.json, so a change here must be made there too.

import (
	"fmt"
	"strconv"
	"strings"

	"zombiezen.com/go/sqlite"
)

const (
	hourSec   = 3600
	deviceCol = "deviceId"
	tsCol     = "ts"
)

// Column is one column of a table as the database declares it.
type Column struct {
	Name     string
	Type     string // the declared type, upper case
	Nullable bool
	Key      bool // part of the primary key
}

// Bucket is one strap's one hour of a table: ts / 3600 as SQLite divides, which rounds toward zero.
type Bucket struct {
	Device string
	Hour   int64
}

func columns(conn *sqlite.Conn, schema, table string) ([]Column, error) {
	var out []Column
	err := run(conn, fmt.Sprintf("PRAGMA %s.table_info(%s)", schema, quote(table)), nil, func(row *sqlite.Stmt) error {
		key := row.ColumnInt64(5) > 0
		out = append(out, Column{
			Name:     row.ColumnText(1),
			Type:     strings.ToUpper(row.ColumnText(2)),
			Nullable: row.ColumnInt64(3) == 0 && !key,
			Key:      key,
		})
		return nil
	})
	return out, err
}

// isSample reports whether a table has the strap and the second in its primary key. Everything else is a small table.
func isSample(cols []Column) bool {
	var device, ts bool
	for _, c := range cols {
		if c.Key && c.Name == deviceCol {
			device = true
		}
		if c.Key && c.Name == tsCol {
			ts = true
		}
	}
	return device && ts
}

func expressions(cols []Column) []string {
	out := []string{"count(*)"}
	for _, c := range cols {
		if c.Name == deviceCol {
			continue
		}
		switch c.Type {
		case "INTEGER":
			out = append(out, fmt.Sprintf("coalesce(sum(%s), 0)", quote(c.Name)))
		case "TEXT", "BLOB":
			out = append(out, fmt.Sprintf("coalesce(sum(length(%s)), 0)", quote(c.Name)))
		}
		if c.Nullable {
			out = append(out, fmt.Sprintf("count(%s)", quote(c.Name)))
		}
	}
	return out
}

// hourPrints returns the checksum of every hour of a table, from fromHour on when it is not nil.
//
// cols are the table's columns as the copy declares them. They are passed in, not read from schema,
// because a delta file's tables carry the rows but not the declarations.
func hourPrints(conn *sqlite.Conn, schema, table string, cols []Column, fromHour *int64) (map[Bucket]string, error) {
	query := fmt.Sprintf("SELECT %s, %s / %d, %s FROM %s.%s",
		quote(deviceCol), quote(tsCol), hourSec, strings.Join(expressions(cols), ", "), schema, quote(table))
	var args []any
	if fromHour != nil {
		query += " WHERE " + quote(tsCol) + " >= ?"
		args = append(args, *fromHour*hourSec)
	}
	query += " GROUP BY 1, 2"
	out := map[Bucket]string{}
	err := run(conn, query, args, func(row *sqlite.Stmt) error {
		parts := make([]string, 0, row.ColumnCount()-2)
		for i := 2; i < row.ColumnCount(); i++ {
			parts = append(parts, strconv.FormatInt(row.ColumnInt64(i), 10))
		}
		out[Bucket{row.ColumnText(0), row.ColumnInt64(1)}] = strings.Join(parts, ",")
		return nil
	})
	return out, err
}

// rowsIn is the row count a checksum starts with.
func rowsIn(print string) int64 {
	first, _, _ := strings.Cut(print, ",")
	n, _ := strconv.ParseInt(first, 10, 64)
	return n
}
