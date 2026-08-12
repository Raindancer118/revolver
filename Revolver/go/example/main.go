package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/Raindancer118/revolver"
)

func main() {
	// Trommel laden – mit Persistenz, damit Sperren Neustarts überleben
	r, err := revolver.Laden(
		[]string{"sk-key1xxxxxxxx", "sk-key2xxxxxxxx", "sk-key3xxxxxxxx"},
		revolver.MitPersistenz(".trommelzustand.json"),
	)
	if err != nil {
		log.Fatal(err)
	}

	ctx := context.Background()

	for anfrage := 1; anfrage <= 15; anfrage++ {
		// Nächste freie Kammer abfeuern
		patrone, err := r.Abfeuern(ctx)
		if err != nil {
			var leer *revolver.ErrTrommelLeer
			if errors.As(err, &leer) {
				fmt.Printf("Trommel leer! Nächste Freigabe: %s\n",
					leer.NaechsteFreigabe.Format(time.RFC3339))
				return
			}
			log.Fatal(err)
		}

		fmt.Printf("Anfrage #%d mit Key ...%s | Verfügbarkeit: %.0f%%\n",
			anfrage,
			patrone.Schluessel[len(patrone.Schluessel)-4:],
			r.Verfuegbarkeit()*100,
		)

		// API-Aufruf
		resp, apiErr := beispielAPIAufruf(patrone.Schluessel)

		switch {
		case apiErr != nil && resp != nil && resp.StatusCode == http.StatusTooManyRequests:
			// Rate-Limit: Sperrzeitpunkt aus Headers lesen
			patrone.RateLimitAusHeaders(resp.Header)
			anfrage-- // diese Anfrage wiederholen
			fmt.Printf("  → Rate-Limit! Kammer gesperrt.\n")

		case apiErr != nil:
			patrone.Fehler()
			fmt.Printf("  → Fehler: %v\n", apiErr)

		default:
			patrone.Erfolg()
		}
	}

	// Trommelstatus ausgeben
	fmt.Println("\nTrommelstatus:")
	for _, s := range r.TrommelStatus() {
		verfuegbar := "✓"
		if !s.Verfuegbar {
			verfuegbar = fmt.Sprintf("✗ (frei um %s)", s.GesperrtBis.Format("15:04:05"))
		}
		fmt.Printf("  Key ...%-8s %s | %d Schüsse | Kapazität ~%.0f (Konfidenz %.0f%%)\n",
			s.Schluessel[len(s.Schluessel)-4:],
			verfuegbar,
			s.Verwendungen,
			s.KapazitaetsEMA,
			s.Konfidenz*100,
		)
	}
}

func beispielAPIAufruf(key string) (*http.Response, error) {
	// Platzhalter – hier würde der echte API-Aufruf stehen
	_ = key
	return nil, nil
}
