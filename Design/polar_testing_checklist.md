# Polar H10 - Testing Checklist

Da testare durante il prossimo allenamento con il Polar H10 connesso.

## Connessione e base
- [ ] Connessione stabile per tutta la durata della sessione (no disconnessioni random)
- [ ] Notifica persistente con BPM aggiornato in tempo reale
- [ ] HR continua a funzionare a schermo spento (foreground service)
- [ ] Disconnessione pulita (notifica sparisce, stato si resetta)

## HeartRateBar negli esercizi
- [ ] Appare in StrengthExerciseScreen (sopra i set, sotto le note)
- [ ] Appare in StretchExerciseScreen
- [ ] Appare in SupersetScreen
- [ ] Non appare se il Polar non e' connesso
- [ ] BPM si aggiorna in tempo reale
- [ ] kcal si accumulano durante la sessione
- [ ] TRIMP si accumula e cambia colore (verde < 50, giallo 50-100, arancione 100-200, rosso > 200)

## Semaforo recovery (automatic peak detection)
- [ ] Diventa rosso automaticamente dopo uno sforzo intenso (senza premere nulla)
- [ ] Transizione rosso -> giallo -> verde mentre recuperi tra i set
- [ ] Si riattiva ad ogni nuovo sforzo (non solo tra esercizi, anche tra set)
- [ ] Soglia ragionevole: non scatta per movimenti minimi, scatta per set veri
- [ ] Se la soglia di 15 BPM sopra il minimo e' troppo alta o bassa, segnalarlo

## ActiveRoutineScreen
- [ ] HeartRateBar visibile sopra "Registra routine"
- [ ] Dopo "Registra routine": kcal e TRIMP mostrati nella sezione progress
- [ ] I valori kcal/TRIMP sono ragionevoli (non zero, non assurdi)

## Profilo utente
- [ ] Eta/peso/sesso salvati e mantenuti tra le sessioni (non si resettano)
- [ ] HRmax calcolato correttamente (208 - 0.7 * eta)

## SessionProgress (tap su giorno nel gitgraph)
- [ ] kcal e TRIMP della sessione visibili
- [ ] Valori corretti (corrispondono a quelli visti durante l'allenamento)

## Calorie - sanity check
- [ ] Sessione di ~60 min: atteso ~200-500 kcal (resistance training)
- [ ] Se il valore sembra troppo alto o basso, segnalarlo con il valore e la durata

## TRIMP - sanity check
- [ ] Sessione leggera: atteso ~30-70
- [ ] Sessione media: atteso ~70-130
- [ ] Sessione intensa: atteso ~130-250
- [ ] Se fuori range, segnalarlo

## Bug o problemi
- [ ] Crash? Quando e cosa stavi facendo
- [ ] UI rotta? Screenshot se possibile
- [ ] Comportamento inatteso? Descrivi
