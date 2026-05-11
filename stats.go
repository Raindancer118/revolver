package revolver

import "time"

// KammerStats enthält eine Momentaufnahme der Statistiken einer einzelnen Kammer.
type KammerStats struct {
	// Schluessel ist der API-Key-Wert dieser Kammer.
	Schluessel string
	// Verfuegbar gibt an, ob die Kammer aktuell nutzbar ist.
	Verfuegbar bool
	// GesperrtBis gibt an, wann die Rate-Limit-Sperre abläuft.
	// Null-Wert bedeutet: keine aktive Sperre.
	GesperrtBis time.Time
	// Verwendungen ist die Gesamtzahl der Schüsse aus dieser Kammer.
	Verwendungen int64
	// Erfolge ist die Anzahl explizit gemeldeter erfolgreicher Anfragen.
	Erfolge int64
	// Fehler ist die Anzahl gemeldeter Nicht-RateLimit-Fehler.
	Fehler int64
	// RateLimitTreffer ist die Anzahl bisheriger Rate-Limit-Sperren.
	RateLimitTreffer int64
	// KapazitaetsEMA ist der geschätzte EMA-Wert für Anfragen pro Rate-Limit-Fenster.
	// Null bedeutet: noch keine Beobachtung vorhanden.
	KapazitaetsEMA float64
	// Konfidenz gibt [0, 1] an, wie zuverlässig die Kapazitätsschätzung ist.
	// Steigt mit jeder Rate-Limit-Beobachtung und sättigt bei 1.0.
	Konfidenz float64
}
