# Polar H10 - Testing Checklist

Da testare durante il prossimo allenamento con il Polar H10 connesso.

## Connessione e base
- [ ] Connessione stabile per tutta la durata della sessione (no disconnessioni random)
- [ ] Notifica persistente con BPM aggiornato in tempo reale
- [ ] HR continua a funzionare a schermo spento (foreground service)
- [ ] Disconnessione pulita (notifica sparisce, stato si resetta)

## HRV Readiness (appena connesso)
- [ ] Appena connetti il Polar appare il countdown "Lie still... 60s" con barra di progresso
- [ ] Dopo 60s mostra il risultato: DELOAD / LIGHT DAY / NORMAL / GOOD / PEAK
- [ ] Mostra Resting HR e LnRMSSD
- [ ] Mostra raccomandazione testuale
- [ ] Primi 7 giorni: mostra "Collecting baseline (X/7 days)"
- [ ] Il resting HR misurato nei 60s e' ragionevole (tipico: 50-80 BPM)

## VO2max
- [ ] Mostrato nella schermata Heart Rate dopo i 60s di readiness
- [ ] Valore ragionevole (tipico: 30-55 ml/kg/min per persona media)
- [ ] Mostrato nella sezione progress dopo "Registra routine"
- [ ] Mostrato in SessionProgressScreen (tap su gitgraph)
- [ ] Salvato correttamente (appare anche riaprendo la sessione)

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
- [ ] Dopo "Registra routine": kcal, TRIMP e VO2max mostrati nella sezione progress
- [ ] I valori kcal/TRIMP sono ragionevoli (non zero, non assurdi)

## Grafici progress (filter chips)
- [ ] Chip "Totale" e bodypart: mostrano tonnage della STESSA routine (come prima)
- [ ] Chip "kcal": grafico storico calorie di TUTTE le routine
- [ ] Chip "TRIMP": grafico storico TRIMP di TUTTE le routine
- [ ] Chip "VO2max": grafico storico VO2max di TUTTE le routine (solo sessioni con valore > 0)
- [ ] Stessi chip funzionano anche in SessionProgressScreen (tap su gitgraph)

## Profilo utente
- [ ] Eta/peso/sesso salvati e mantenuti tra le sessioni (non si resettano)
- [ ] HRmax calcolato correttamente (208 - 0.7 * eta)

## Calorie - sanity check
- [ ] Sessione di ~60 min: atteso ~200-500 kcal (resistance training)
- [ ] Se il valore sembra troppo alto o basso, segnalarlo con il valore e la durata

## TRIMP - sanity check
- [ ] Sessione leggera: atteso ~30-70
- [ ] Sessione media: atteso ~70-130
- [ ] Sessione intensa: atteso ~130-250
- [ ] Se fuori range, segnalarlo

## ECG recording + raw file handling (post server-side migration — see CONVENTIONS.md § ECG raw file: send-then-delete)
- [ ] Durante la routine: nessun crash dovuto allo stream ECG (funziona in background a schermo spento)
- [ ] Su "Registra routine": nessun ritardo eccessivo (non c'è più analisi Pan-Tompkins sul telefono — deep analysis è server-side)
- [ ] Il file `.ecg` grezzo viene cancellato dopo l'upload al server se sync è configurata/attiva, o immediatamente se non lo è (controlla `/data/data/com.mygymapp/files/gymdata/ecg/` vuoto dopo la registrazione)
- [ ] Cardiac drift: dopo ≥5 minuti mostra il valore BPM/min con label (normal / moderate / high) — questo è ancora calcolato localmente dalla serie HR, non dall'ECG grezzo
- [ ] Valore realistico (normale < 0.5, moderato 0.5-1.0, alto > 1.0)

Le vecchie voci "SessionProgressScreen → card ECG Analysis (beats/RMSSD/PAC/Pauses/Irregular)" non si applicano più: quell'analisi girava sul telefono via `EcgAnalyzer`/`PolarManager.analyzeSessionEcg()`, che esistono ancora nel codice ma non sono più chiamati da nulla — l'analisi profonda dell'ECG è ora interamente server-side (vedi [SYNC.md](../SYNC.md#fourth-record-type-raw-ecg)).

## Live ECG card (ActiveRoutineScreen)
- [ ] La waveform ECG scorre fluida (no scatti o frame persi)
- [ ] Dopo ~2 secondi di streaming iniziano a comparire i beats contati
- [ ] **Regular %** a riposo tra 97-100% con elettrodi ben bagnati
- [ ] **Regular %** durante sforzo resta > 95% (sotto sforzo intenso puo' scendere un po')
- [ ] Pallino Regular%:
  - [ ] Verde se >= 98%
  - [ ] Giallo se 95-98%
  - [ ] Rosso se < 95%
- [ ] **Uneven**: a riposo resta basso (con la regola "2 consecutivi" dovrebbe essere vicino a 0 in condizioni normali)
- [ ] Se vedi molti Uneven a riposo: probabile segnale sporco (elettrodi asciutti, fascia non aderente)
- [ ] **Premature**: se ne compare qualcuno a riposo isolato, e' normale (extrasistoli benigne)
- [ ] **Pauses**: a riposo dovrebbero essere 0 (pause > 2s sono rare)
- [ ] **Drift** live: dopo 5 minuti inizia a mostrare un valore BPM/min con pallino colorato
  - [ ] Pallino grigio prima dei 5 minuti ("waiting")
  - [ ] Verde / giallo / rosso in base alla pendenza

## Comparazione valori live vs post-sessione
- [ ] Il **drift** salvato coincide con quello mostrato live (o molto simile) — questo è l'unico dei tre ancora calcolato e persistito lato telefono
- ~~beats/irregolarità finali~~ — non più applicabile: erano output di `EcgAnalyzer.analyze()`, che non gira più (deep analysis è server-side, vedi sopra)

## Extended metrics saved locally (post-session)
Solo `restingHr`, `hrr60s` e `cardiacDriftBpmMin` sono ancora calcolati sul telefono dalla serie HR (non dall'ECG grezzo) — vedi [CONVENTIONS.md § ECG raw file](../CONVENTIONS.md#ecg-raw-file-send-then-delete-no-local-analysis-fallback). SDNN/pNN50/Poincaré SD1-SD2/AFib screening restano a zero nella sessione salvata: erano prodotti da `EcgAnalyzer.analyze()`, ora inutilizzato — l'unico modo di vederli calcolati è lato server, fuori da questo checklist.
- [ ] **Resting HR**: valore salvato ragionevole (50-80 BPM tipico). Dovrebbe coincidere con il min dei 60s di readiness
- [ ] **HRR (1 min)**: valore BPM — atteso 15-35 BPM per persona allenata. Label "low/ok/good/excellent" sensata
  - [ ] < 12 = "low", 12-20 = "ok", 20-30 = "good", > 30 = "excellent"

## Sanity check tra sessioni
- [ ] Stessi parametri con sessione simile danno valori simili (variabilita' 10-30% normale)
- [ ] Resting HR stabile ±5 BPM tra giorni (senza malattia/stress)
- [ ] Se HRR cala brutalmente su piu' sessioni → segnalare (possibile sovrallenamento)

## Cardio Trend (schermata Heart Rate, sopra il profilo)
- [ ] All'apertura compare la card "📊 Cardio trend · last 4 weeks"
- [ ] Conteggio sessioni e durata media corretti (confronta col numero di sessioni completate negli ultimi 28 giorni)
- [ ] Se hai < 2 sessioni recenti mostra "Not enough data yet"
- [ ] Griglia 2×3 con 6 metriche visibili:
  - [ ] Resting HR
  - [ ] HRR (1 min)
  - [ ] VO2max
  - [ ] RMSSD
  - [ ] SDNN
  - [ ] Cardiac drift
  - [ ] SD2/SD1 ratio (7ª, compare in basso)
- [ ] Ogni cella mostra: nome, pallino semaforo, mini-sparkline, valore attuale + unita', delta + freccia
- [ ] Sparkline ha il colore del pallino semaforo (verde/giallo/rosso)
- [ ] Freccia direzione coerente col delta (↑ se positivo, ↓ se negativo, → se flat)

## Semaforo sensato per ogni metrica (logica direzionale)
- [ ] Resting HR: verde se in calo, rosso se sale > 10% e/o > 85 BPM assoluti
- [ ] HRR: verde se sale, rosso se < 12 BPM assoluti
- [ ] VO2max: verde se sale, giallo/rosso se cala > 10%
- [ ] RMSSD / SDNN: verde se sale o stabile, giallo/rosso se cala > 10%
- [ ] Cardiac drift: verde se < 0.5 stabile, rosso se > 1.0
- [ ] SD2/SD1 ratio: verde se 1.5-4.5, giallo/rosso se fuori

## Sezione Ritmo (contatori 30 giorni)
- [ ] AFib episodes: 0 = verde, 1-2 giallo, >2 rosso
- [ ] Pauses: 0 = verde, 1-2 giallo, >2 rosso
- [ ] Premature: ≤20 verde, 21-60 giallo, >60 rosso
- [ ] Uneven: ≤30 verde, 31-100 giallo, >100 rosso

## Alerts (conditional — appaiono solo se scatta il trigger)
- [ ] AFib > 0 episodi → alert "consult a physician if recurring"
- [ ] Pauses > 2 in 4 settimane → alert "uncommon, mention to doctor"
- [ ] Resting HR up ≥ 5 BPM vs baseline con ≥3 sessioni per lato → alert "possible fatigue or illness"
- [ ] HRR media recente < 12 → alert "poor recovery trend"
- [ ] < 4 sessioni totali → alert "trends not yet reliable"

## Bug o problemi
- [ ] Crash? Quando e cosa stavi facendo
- [ ] UI rotta? Screenshot se possibile
- [ ] Comportamento inatteso? Descrivi
