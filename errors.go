package revolver

import (
	"fmt"
	"time"
)

// ErrTrommelLeer wird zurückgegeben, wenn alle Kammern der Trommel
// aktuell durch Rate-Limits gesperrt sind.
type ErrTrommelLeer struct {
	// NaechsteFreigabe ist der Zeitpunkt, ab dem die nächste Kammer wieder verfügbar ist.
	NaechsteFreigabe time.Time
}

func (e *ErrTrommelLeer) Error() string {
	verbleibend := time.Until(e.NaechsteFreigabe).Round(time.Second)
	return fmt.Sprintf(
		"revolver: alle Kammern erschöpft — nächste Freigabe um %s (in %s)",
		e.NaechsteFreigabe.Format(time.RFC3339),
		verbleibend,
	)
}
