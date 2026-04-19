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

## ECG recording + post-session analysis (7.8 + 7.9)
- [ ] Durante la routine: nessun crash dovuto allo stream ECG (funziona in background a schermo spento)
- [ ] Su "Registra routine": nessun ritardo eccessivo (l'analisi Pan-Tompkins dura poco, ma un minuto ok)
- [ ] Il file ECG viene cancellato dopo l'analisi (controlla `/data/data/com.mygymapp/files/gymdata/ecg/` vuoto dopo la registrazione)
- [ ] SessionProgressScreen mostra la card "ECG Analysis":
  - [ ] beats detected ragionevole (frequenza media × minuti)
  - [ ] avg BPM coerente con quello visto durante la sessione
  - [ ] RMSSD visualizzato (tipicamente 10-50 ms durante sforzo)
  - [ ] PAC / Pauses / Irregular: se 0 mostra "No anomalies detected", altrimenti li elenca con disclaimer
- [ ] Cardiac drift: dopo ≥5 minuti mostra il valore BPM/min con label (normal / moderate / high)
- [ ] Valore realistico (normale < 0.5, moderato 0.5-1.0, alto > 1.0)

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
- [ ] Quando tappi "Registra routine", il numero di **beats** finale coincide circa con quello live
- [ ] Il **drift** salvato coincide con quello mostrato live (o molto simile)
- [ ] Le **irregolarita'** totali sono simili a quelle live (accetta ±10% di differenza, gli algoritmi streaming vs batch hanno piccole differenze)

## Extended ECG metrics (post-session, stealth)
- [ ] **Resting HR**: valore salvato ragionevole (50-80 BPM tipico). Dovrebbe coincidere con il min dei 60s di readiness
- [ ] **HRR (1 min)**: valore BPM — atteso 15-35 BPM per persona allenata. Label "low/ok/good/excellent" sensata
  - [ ] < 12 = "low", 12-20 = "ok", 20-30 = "good", > 30 = "excellent"
- [ ] **SDNN**: in ms, tipico 30-80 durante sessione mista
- [ ] **pNN50**: %, tipico 5-30% a seconda dell'intensita'
- [ ] **Poincare SD1/SD2/ratio**: SD1 vicino a RMSSD/√2, SD2 > SD1, ratio 1-4 (rest) o piu' alto sotto sforzo
- [ ] **AFib screening**: a sessione regolare = 0 episodi. Se compare qualche episodio isolato puo' essere falso positivo (PAC frequenti, movimento); se persistente in piu' sessioni → attenzione

## Sanity check tra sessioni
- [ ] Stessi parametri con sessione simile danno valori simili (variabilita' 10-30% normale)
- [ ] Resting HR stabile ±5 BPM tra giorni (senza malattia/stress)
- [ ] Se HRR cala brutalmente su piu' sessioni → segnalare (possibile sovrallenamento)

## Bug o problemi
- [ ] Crash? Quando e cosa stavi facendo
- [ ] UI rotta? Screenshot se possibile
- [ ] Comportamento inatteso? Descrivi
