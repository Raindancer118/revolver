package revolver_test

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/Raindancer118/revolver"
)

func TestTrommelDrehtSich(t *testing.T) {
	r, err := revolver.Laden([]string{"key1", "key2", "key3"})
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	erwartet := []string{"key1", "key2", "key3", "key1", "key2", "key3"}
	for i, erw := range erwartet {
		p, err := r.Abfeuern(ctx)
		if err != nil {
			t.Fatalf("Abfeuern() Fehler bei i=%d: %v", i, err)
		}
		if p.Schluessel != erw {
			t.Errorf("Abfeuern()[%d] = %q, erwartet %q", i, p.Schluessel, erw)
		}
	}
}

func TestGesperrteKammerWirdUebersprungen(t *testing.T) {
	r, err := revolver.Laden([]string{"key1", "key2", "key3"})
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	p, _ := r.Abfeuern(ctx)
	if p.Schluessel != "key1" {
		t.Fatalf("erwartet key1, bekam %s", p.Schluessel)
	}
	p.RateLimit(time.Now().Add(1 * time.Hour))

	naechste, _ := r.Abfeuern(ctx)
	if naechste.Schluessel == "key1" {
		t.Error("gesperrte Kammer key1 sollte übersprungen werden")
	}
}

func TestAlleTrommelLeerFehler(t *testing.T) {
	r, err := revolver.Laden([]string{"key1", "key2"})
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	p1, _ := r.Abfeuern(ctx)
	p2, _ := r.Abfeuern(ctx)
	sperrzeit := time.Now().Add(5 * time.Minute)
	p1.RateLimit(sperrzeit)
	p2.RateLimit(sperrzeit)

	_, err = r.Abfeuern(ctx)
	if err == nil {
		t.Fatal("Fehler erwartet wenn alle Kammern erschöpft")
	}

	var trommelLeer *revolver.ErrTrommelLeer
	if !errors.As(err, &trommelLeer) {
		t.Fatalf("ErrTrommelLeer erwartet, bekam %T: %v", err, err)
	}
	if trommelLeer.NaechsteFreigabe.IsZero() {
		t.Error("NaechsteFreigabe sollte nicht null sein")
	}
}

func TestVerfuegbarkeit(t *testing.T) {
	r, err := revolver.Laden([]string{"key1", "key2", "key3", "key4"})
	if err != nil {
		t.Fatal(err)
	}

	if got := r.Verfuegbarkeit(); got != 1.0 {
		t.Errorf("initiale Verfuegbarkeit() = %f, erwartet 1.0", got)
	}

	ctx := context.Background()
	p1, _ := r.Abfeuern(ctx)
	p2, _ := r.Abfeuern(ctx)
	p1.RateLimit(time.Now().Add(1 * time.Hour))
	p2.RateLimit(time.Now().Add(1 * time.Hour))

	if got := r.Verfuegbarkeit(); got != 0.5 {
		t.Errorf("Verfuegbarkeit() nach 2 Sperren = %f, erwartet 0.5", got)
	}
}

func TestKapazitaetsSchätzung(t *testing.T) {
	r, err := revolver.Laden([]string{"key1"})
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	// 5 Runden: je 5 Schüsse, dann sofortiges Rate-Limit
	for runde := 0; runde < 5; runde++ {
		var letzte *revolver.Patrone
		for i := 0; i < 5; i++ {
			letzte, _ = r.Abfeuern(ctx)
		}
		// Sofort-Freigabe für nächste Runde
		letzte.RateLimit(time.Now().Add(-time.Nanosecond))
	}

	status := r.TrommelStatus()
	if len(status) != 1 {
		t.Fatal("genau 1 Kammer erwartet")
	}
	s := status[0]
	if s.KapazitaetsEMA <= 0 {
		t.Error("positive KapazitaetsEMA nach Beobachtungen erwartet")
	}
	if s.Konfidenz <= 0 {
		t.Error("positive Konfidenz nach Beobachtungen erwartet")
	}
}

func TestLadenOhneSchluessel(t *testing.T) {
	_, err := revolver.Laden(nil)
	if err == nil {
		t.Error("Fehler für nil-Schlüssel erwartet")
	}
	_, err = revolver.Laden([]string{})
	if err == nil {
		t.Error("Fehler für leere Schlüsselliste erwartet")
	}
}

func TestKontextAbbruch(t *testing.T) {
	r, err := revolver.Laden([]string{"key1"}, revolver.MitWarten())
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	p, _ := r.Abfeuern(ctx)
	p.RateLimit(time.Now().Add(10 * time.Minute))

	abbruchCtx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	_, err = r.Abfeuern(abbruchCtx)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("DeadlineExceeded erwartet, bekam %v", err)
	}
}

func TestPersistenz(t *testing.T) {
	pfad := filepath.Join(t.TempDir(), "trommel.json")

	r1, err := revolver.Laden([]string{"key1", "key2"}, revolver.MitPersistenz(pfad))
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	p, _ := r1.Abfeuern(ctx)
	sperrzeit := time.Now().Add(10 * time.Minute)
	p.RateLimit(sperrzeit)

	// Neuer Revolver mit derselben Datei
	r2, err := revolver.Laden([]string{"key1", "key2"}, revolver.MitPersistenz(pfad))
	if err != nil {
		t.Fatal(err)
	}

	fuerKey1 := func(stats []revolver.KammerStats) revolver.KammerStats {
		for _, s := range stats {
			if s.Schluessel == "key1" {
				return s
			}
		}
		t.Fatal("key1 nicht in Stats gefunden")
		return revolver.KammerStats{}
	}

	s := fuerKey1(r2.TrommelStatus())
	if s.Verfuegbar {
		t.Error("key1 sollte nach dem Laden noch gesperrt sein")
	}
}

func TestParseRateLimitHeaders_RetryAfterSekunden(t *testing.T) {
	h := http.Header{"Retry-After": []string{"60"}}
	d, ok := revolver.ParseRateLimitHeaders(h)
	if !ok {
		t.Fatal("Header sollte geparst werden")
	}
	if d.Before(time.Now().Add(59*time.Second)) || d.After(time.Now().Add(61*time.Second)) {
		t.Errorf("Deadline %v nicht ca. 60s in der Zukunft", d)
	}
}

func TestParseRateLimitHeaders_XRateLimitReset(t *testing.T) {
	zukunft := time.Now().Add(5 * time.Minute)
	h := make(http.Header)
	h.Set("X-RateLimit-Reset", strconv.FormatInt(zukunft.Unix(), 10))
	d, ok := revolver.ParseRateLimitHeaders(h)
	if !ok {
		t.Fatal("Header sollte geparst werden")
	}
	if d.Unix() != zukunft.Unix() {
		t.Errorf("Deadline %v != erwartet %v", d, zukunft)
	}
}

func TestParseRateLimitHeaders_KeinHeader(t *testing.T) {
	_, ok := revolver.ParseRateLimitHeaders(http.Header{})
	if ok {
		t.Error("false für leere Header erwartet")
	}
}

func TestPersistenzDateiExistiertNicht(t *testing.T) {
	pfad := filepath.Join(t.TempDir(), "existiert_nicht.json")
	_, err := revolver.Laden([]string{"key1"}, revolver.MitPersistenz(pfad))
	if err != nil {
		t.Errorf("kein Fehler erwartet für fehlende Datei, bekam: %v", err)
	}
}

func TestTrommelStatusVollstaendig(t *testing.T) {
	r, _ := revolver.Laden([]string{"key1", "key2"})
	ctx := context.Background()
	p, _ := r.Abfeuern(ctx)
	p.Erfolg()
	p2, _ := r.Abfeuern(ctx)
	p2.Fehler()

	stats := r.TrommelStatus()
	if len(stats) != 2 {
		t.Fatalf("2 Kammer-Stats erwartet, bekam %d", len(stats))
	}

	if stats[0].Verwendungen != 1 {
		t.Errorf("key1 Verwendungen = %d, erwartet 1", stats[0].Verwendungen)
	}
	if stats[1].Verwendungen != 1 {
		t.Errorf("key2 Verwendungen = %d, erwartet 1", stats[1].Verwendungen)
	}
}

func TestHauptpfad(t *testing.T) {
	if os.Getenv("CI") == "" {
		t.Skip("Hauptpfad-Test nur in CI")
	}

	r, err := revolver.Laden([]string{"dummy-key"})
	if err != nil {
		t.Fatalf("Laden fehlgeschlagen: %v", err)
	}

	ctx := context.Background()
	p, err := r.Abfeuern(ctx)
	if err != nil {
		t.Fatalf("Abfeuern fehlgeschlagen: %v", err)
	}
	if p.Schluessel == "" {
		t.Error("Patrone hat keinen Schlüssel")
	}
	p.Erfolg()

	if r.Verfuegbarkeit() != 1.0 {
		t.Error("Verfügbarkeit sollte 1.0 sein nach Erfolg")
	}
}
