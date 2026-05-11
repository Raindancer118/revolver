package revolver

import "time"

const (
	// emaAlpha steuert, wie schnell die EMA auf neue Beobachtungen reagiert.
	// 0.3 = 30% Gewicht auf neue Daten, 70% auf den bisherigen Schnitt.
	emaAlpha = 0.3

	// konfidenzsaettigung: ab dieser Anzahl an Beobachtungen gilt die Schätzung als vollständig zuverlässig.
	konfidenzsaettigung = 10
)

// kammer repräsentiert eine einzelne Kammer der Trommel mit einem API-Key.
type kammer struct {
	schluessel string
	gesperrtBis time.Time

	verwendungen    int64
	erfolge         int64
	fehler          int64
	rateLimitTreffer int64

	// sitzungsVerwendungen zählt Verwendungen seit dem letzten Rate-Limit,
	// um die Kapazität pro Fenster zu schätzen.
	sitzungsVerwendungen int64

	// kapazitaetsEMA ist der exponentiell gewichtete Schnitt der Verwendungen
	// pro Rate-Limit-Fenster. Null bedeutet: noch keine Beobachtung.
	kapazitaetsEMA float64

	// beobachtungen zählt, wie oft ein Rate-Limit für die EMA-Berechnung
	// verwendet wurde (Grundlage für die Konfidenz).
	beobachtungen int
}

func neueKammer(schluessel string) *kammer {
	return &kammer{schluessel: schluessel}
}

func (k *kammer) verwendungVormerken() {
	k.verwendungen++
	k.sitzungsVerwendungen++
}

func (k *kammer) rateLimitVormerken(bis time.Time) {
	k.gesperrtBis = bis
	k.rateLimitTreffer++

	if k.sitzungsVerwendungen > 0 {
		probe := float64(k.sitzungsVerwendungen)
		if k.kapazitaetsEMA == 0 {
			k.kapazitaetsEMA = probe
		} else {
			k.kapazitaetsEMA = emaAlpha*probe + (1-emaAlpha)*k.kapazitaetsEMA
		}
		k.beobachtungen++
		k.sitzungsVerwendungen = 0
	}
}

// konfidenz gibt [0, 1] zurück – wie verlässlich die Kapazitätsschätzung ist.
// Erreicht 1.0 nach konfidenzsaettigung Beobachtungen.
func (k *kammer) konfidenz() float64 {
	if k.beobachtungen == 0 {
		return 0
	}
	c := float64(k.beobachtungen) / float64(konfidenzsaettigung)
	if c > 1.0 {
		return 1.0
	}
	return c
}

// kammerZustand ist die serialisierbare Repräsentation einer Kammer.
type kammerZustand struct {
	Schluessel           string    `json:"schluessel"`
	GesperrtBis          time.Time `json:"gesperrt_bis,omitempty"`
	Verwendungen         int64     `json:"verwendungen"`
	Erfolge              int64     `json:"erfolge"`
	Fehler               int64     `json:"fehler"`
	RateLimitTreffer     int64     `json:"rate_limit_treffer"`
	SitzungsVerwendungen int64     `json:"sitzungs_verwendungen"`
	KapazitaetsEMA       float64   `json:"kapazitaets_ema"`
	Beobachtungen        int       `json:"beobachtungen"`
}

func (k *kammer) zuZustand() kammerZustand {
	return kammerZustand{
		Schluessel:           k.schluessel,
		GesperrtBis:          k.gesperrtBis,
		Verwendungen:         k.verwendungen,
		Erfolge:              k.erfolge,
		Fehler:               k.fehler,
		RateLimitTreffer:     k.rateLimitTreffer,
		SitzungsVerwendungen: k.sitzungsVerwendungen,
		KapazitaetsEMA:       k.kapazitaetsEMA,
		Beobachtungen:        k.beobachtungen,
	}
}

func (k *kammer) ausZustand(z kammerZustand) {
	k.gesperrtBis = z.GesperrtBis
	k.verwendungen = z.Verwendungen
	k.erfolge = z.Erfolge
	k.fehler = z.Fehler
	k.rateLimitTreffer = z.RateLimitTreffer
	k.sitzungsVerwendungen = z.SitzungsVerwendungen
	k.kapazitaetsEMA = z.KapazitaetsEMA
	k.beobachtungen = z.Beobachtungen
}
