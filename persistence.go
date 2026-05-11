package revolver

import (
	"encoding/json"
	"os"
)

// Persister ist das Interface für das Speichern und Laden des Trommelzustands.
// Implementiere dieses Interface für eigene Speicher-Backends (z.B. Redis, DB).
type Persister interface {
	Speichern(zustaende []kammerZustand) error
	Laden() ([]kammerZustand, error)
}

// dateiSpeicher speichert den Trommelzustand als JSON-Datei.
type dateiSpeicher struct {
	pfad string
}

func (d *dateiSpeicher) Speichern(zustaende []kammerZustand) error {
	daten, err := json.MarshalIndent(zustaende, "", "  ")
	if err != nil {
		return err
	}
	// Atomisches Schreiben via temporärer Datei
	tmp := d.pfad + ".tmp"
	if err := os.WriteFile(tmp, daten, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, d.pfad)
}

func (d *dateiSpeicher) Laden() ([]kammerZustand, error) {
	daten, err := os.ReadFile(d.pfad)
	if err != nil {
		return nil, err
	}
	var zustaende []kammerZustand
	return zustaende, json.Unmarshal(daten, &zustaende)
}
