package revolver

import (
	"net/http"
	"time"
)

// Patrone repräsentiert einen gezogenen API-Key aus der Trommel.
// Nach der Verwendung muss das Ergebnis über Erfolg(), RateLimit()
// oder Fehler() zurückgemeldet werden, damit die Trommel lernen kann.
type Patrone struct {
	// Schluessel ist der eigentliche API-Key-Wert.
	Schluessel string
	revolver   *Revolver
}

// Erfolg meldet eine erfolgreiche API-Anfrage zurück.
// Verbessert die Kapazitätsschätzung der Kammer.
func (p *Patrone) Erfolg() {
	p.revolver.meldeErfolg(p.Schluessel)
}

// RateLimit meldet, dass diese Kammer ihr Rate-Limit erreicht hat,
// und gibt an, bis wann sie gesperrt sein soll.
// Danach wird die Kammer bei Abfeuern() übersprungen.
func (p *Patrone) RateLimit(bis time.Time) {
	p.revolver.meldeRateLimit(p.Schluessel, bis)
}

// RateLimitAusHeaders liest den Sperrzeitpunkt aus den HTTP-Response-Headern
// und meldet das Rate-Limit entsprechend. Wenn keine verwertbaren Header
// vorhanden sind, wird ein Fallback von 60 Sekunden verwendet.
func (p *Patrone) RateLimitAusHeaders(h http.Header) {
	bis, ok := ParseRateLimitHeaders(h)
	if !ok {
		bis = time.Now().Add(60 * time.Second)
	}
	p.RateLimit(bis)
}

// Fehler meldet einen Nicht-RateLimit-Fehler für diese Kammer.
func (p *Patrone) Fehler() {
	p.revolver.meldeFehler(p.Schluessel)
}
