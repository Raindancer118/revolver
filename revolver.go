// Package revolver verwaltet einen rotierenden Pool von API-Keys
// nach dem Revolver-Prinzip: jede Kammer enthält eine Patrone (API-Key),
// die Trommel dreht sich nach jedem Schuss zur nächsten Kammer weiter.
package revolver

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"
)

// Revolver verwaltet eine Trommel mit API-Key-Kammern.
// Er ist nebenläufig sicher.
type Revolver struct {
	mu       sync.Mutex
	trommel  []*kammer
	position int // aktuelle Trommelposition
	cfg      config
	speicher Persister
}

type config struct {
	wartenBeiLeer bool
	persistPfad   string
	persister     Persister
}

// Option konfiguriert einen Revolver.
type Option func(*config)

// MitWarten lässt Abfeuern() blockieren, bis eine Kammer wieder frei ist,
// statt sofort einen Fehler zurückzugeben.
func MitWarten() Option {
	return func(c *config) { c.wartenBeiLeer = true }
}

// MitPersistenz aktiviert dateibasierte Zustandsspeicherung am angegebenen Pfad.
func MitPersistenz(pfad string) Option {
	return func(c *config) { c.persistPfad = pfad }
}

// MitPersister setzt ein benutzerdefiniertes Speicher-Backend.
func MitPersister(p Persister) Option {
	return func(c *config) { c.persister = p }
}

// Laden erstellt einen neuen Revolver und lädt die Kammern mit den gegebenen API-Keys.
func Laden(schluessel []string, opts ...Option) (*Revolver, error) {
	if len(schluessel) == 0 {
		return nil, fmt.Errorf("revolver: mindestens ein Schlüssel wird benötigt")
	}

	cfg := config{}
	for _, opt := range opts {
		opt(&cfg)
	}

	trommel := make([]*kammer, len(schluessel))
	for i, s := range schluessel {
		trommel[i] = neueKammer(s)
	}

	r := &Revolver{
		trommel: trommel,
		cfg:     cfg,
	}

	if cfg.persister != nil {
		r.speicher = cfg.persister
	} else if cfg.persistPfad != "" {
		r.speicher = &dateiSpeicher{pfad: cfg.persistPfad}
	}

	if r.speicher != nil {
		if err := r.laden(); err != nil && !os.IsNotExist(err) {
			return nil, fmt.Errorf("revolver: zustand laden: %w", err)
		}
	}

	return r, nil
}

// Abfeuern gibt die nächste verfügbare Patrone aus der Trommel zurück.
// Die Trommel dreht sich dabei zur nächsten Kammer weiter.
//
// Wenn alle Kammern gesperrt sind und MitWarten() nicht gesetzt ist,
// wird *ErrTrommelLeer zurückgegeben, das den Zeitpunkt der nächsten
// Freigabe enthält.
//
// Mit MitWarten() blockiert die Methode, bis der Kontext abläuft
// oder eine Kammer wieder frei wird.
func (r *Revolver) Abfeuern(ctx context.Context) (*Patrone, error) {
	for {
		r.mu.Lock()
		k, naechsteFrei := r.naechsteKammer()
		if k != nil {
			k.verwendungVormerken()
			r.mu.Unlock()
			r.speichern()
			return &Patrone{Schluessel: k.schluessel, revolver: r}, nil
		}
		r.mu.Unlock()

		if !r.cfg.wartenBeiLeer {
			return nil, &ErrTrommelLeer{NaechsteFreigabe: naechsteFrei}
		}

		warten := time.Until(naechsteFrei)
		if warten <= 0 {
			continue
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(warten):
		}
	}
}

// Verfuegbarkeit gibt den Anteil der aktuell nutzbaren Kammern zurück (0.0–1.0).
func (r *Revolver) Verfuegbarkeit() float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	frei := 0
	jetzt := time.Now()
	for _, k := range r.trommel {
		if k.gesperrtBis.IsZero() || k.gesperrtBis.Before(jetzt) {
			frei++
		}
	}
	return float64(frei) / float64(len(r.trommel))
}

// TrommelStatus gibt eine Momentaufnahme der Statistiken aller Kammern zurück.
func (r *Revolver) TrommelStatus() []KammerStats {
	r.mu.Lock()
	defer r.mu.Unlock()
	stats := make([]KammerStats, len(r.trommel))
	jetzt := time.Now()
	for i, k := range r.trommel {
		stats[i] = KammerStats{
			Schluessel:        k.schluessel,
			Verfuegbar:        k.gesperrtBis.IsZero() || k.gesperrtBis.Before(jetzt),
			GesperrtBis:       k.gesperrtBis,
			Verwendungen:      k.verwendungen,
			Erfolge:           k.erfolge,
			Fehler:            k.fehler,
			RateLimitTreffer:  k.rateLimitTreffer,
			KapazitaetsEMA:    k.kapazitaetsEMA,
			Konfidenz:         k.konfidenz(),
		}
	}
	return stats
}

// meldeErfolg registriert eine erfolgreiche Verwendung einer Patrone.
func (r *Revolver) meldeErfolg(schluessel string) {
	r.mu.Lock()
	k := r.kammerFuer(schluessel)
	if k != nil {
		k.erfolge++
	}
	r.mu.Unlock()
	r.speichern()
}

// meldeRateLimit markiert eine Kammer als gesperrt bis zum angegebenen Zeitpunkt.
func (r *Revolver) meldeRateLimit(schluessel string, bis time.Time) {
	r.mu.Lock()
	k := r.kammerFuer(schluessel)
	if k != nil {
		k.rateLimitVormerken(bis)
	}
	r.mu.Unlock()
	r.speichern()
}

// meldeFehler registriert einen Nicht-RateLimit-Fehler für eine Kammer.
func (r *Revolver) meldeFehler(schluessel string) {
	r.mu.Lock()
	k := r.kammerFuer(schluessel)
	if k != nil {
		k.fehler++
	}
	r.mu.Unlock()
	r.speichern()
}

// naechsteKammer findet die nächste freie Kammer ab der aktuellen Position.
// Gibt die Kammer und den frühesten Freigabe-Zeitpunkt bei voller Trommel zurück.
// Muss unter r.mu gehalten aufgerufen werden.
func (r *Revolver) naechsteKammer() (*kammer, time.Time) {
	jetzt := time.Now()
	frueheste := time.Time{}

	for i := 0; i < len(r.trommel); i++ {
		idx := (r.position + i) % len(r.trommel)
		k := r.trommel[idx]
		if k.gesperrtBis.IsZero() || k.gesperrtBis.Before(jetzt) {
			r.position = (idx + 1) % len(r.trommel)
			return k, time.Time{}
		}
		if frueheste.IsZero() || k.gesperrtBis.Before(frueheste) {
			frueheste = k.gesperrtBis
		}
	}
	return nil, frueheste
}

func (r *Revolver) kammerFuer(schluessel string) *kammer {
	for _, k := range r.trommel {
		if k.schluessel == schluessel {
			return k
		}
	}
	return nil
}

func (r *Revolver) speichern() {
	if r.speicher == nil {
		return
	}
	r.mu.Lock()
	zust := make([]kammerZustand, len(r.trommel))
	for i, k := range r.trommel {
		zust[i] = k.zuZustand()
	}
	r.mu.Unlock()
	_ = r.speicher.Speichern(zust)
}

func (r *Revolver) laden() error {
	zustaende, err := r.speicher.Laden()
	if err != nil {
		return err
	}
	zMap := make(map[string]kammerZustand, len(zustaende))
	for _, z := range zustaende {
		zMap[z.Schluessel] = z
	}
	for _, k := range r.trommel {
		if z, ok := zMap[k.schluessel]; ok {
			k.ausZustand(z)
		}
	}
	return nil
}
