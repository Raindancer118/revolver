package revolver

import (
	"net/http"
	"strconv"
	"time"
)

// ParseRateLimitHeaders extrahiert den Sperrzeitpunkt aus verbreiteten
// HTTP-Response-Headern. Es werden folgende Header geprüft (in Reihenfolge):
//
//   - Retry-After (Sekunden oder HTTP-Datum)
//   - X-RateLimit-Reset (Unix-Timestamp)
//   - RateLimit-Reset (Unix-Timestamp, IETF-Entwurf)
//
// Gibt (Zeitpunkt, true) zurück, wenn ein Header gefunden wurde, sonst (zero, false).
func ParseRateLimitHeaders(h http.Header) (time.Time, bool) {
	if v := h.Get("Retry-After"); v != "" {
		if sek, err := strconv.ParseInt(v, 10, 64); err == nil {
			return time.Now().Add(time.Duration(sek) * time.Second), true
		}
		if t, err := http.ParseTime(v); err == nil {
			return t, true
		}
	}

	for _, header := range []string{"X-RateLimit-Reset", "RateLimit-Reset"} {
		if v := h.Get(header); v != "" {
			if ts, err := strconv.ParseInt(v, 10, 64); err == nil {
				return time.Unix(ts, 0), true
			}
		}
	}

	return time.Time{}, false
}
