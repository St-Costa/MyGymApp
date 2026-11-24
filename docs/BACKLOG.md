# Backlog — cose da fare un giorno

Questa lista esiste perché una futura analisi ("cerca problemi nella repo") ritrovi
il lavoro noto-pendente invece di riscoprirlo. Ogni voce dice *cosa*, *perché è
ferma* e *da dove partire*. Ordinate per valore/costo. Le decisioni "non si fa"
stanno in `CONVENTIONS.md` (Non-goals: i18n multi-lingua, layout adaptive) — qui
solo cose vive.

## Serve il telefono

- [ ] **Smoke release + sync reale combinati.** Self-test 10/10 su R8 fatto (Phase
      180); il sync reale è stato fatto sul debug, mai dalla release. Rifare
      `assembleRelease` → firma → backup → install → self-test → un invio vero.
- [ ] **Sessione vera con Polar + bilancia** (`docs/polar/testing-checklist.md`),
      incl. verifica visiva UI inglese (overflow), slider beep col preview,
      traversata TalkBack degli stepper.
- [ ] **Major dipendenze sul device.** Coil 2→3, OkHttp 4→5, security-crypto
      1.0.0→1.1.x: non aggiornate alla cieca perché toccano foto/rete/Keystore.
      Dopo il bump, rigenerare `verification-metadata.xml` e rifare lo smoke.
- [ ] **Split collector HeartRateScreen.** Oggi ricompone tutto a ~1Hz; prima
      misurare i tempi di ricomposizione sul telefono, poi splittare i collector
      solo se serve (senza misure è speculazione).

## Codice (fattibile senza telefono)

- [ ] **Merge UI nel restore.** Oggi l'overwrite salva in `gymdata/_conflicts/<ts>/`
      e conta gli "overwritten" (Phase 172): manca la schermata di scelta riga per
      riga (tieni il mio / tieni il suo). Vedi `BACKUP.md` §3.6/§5.
- [ ] **Equivalenza switch nel picker e nel superset.** Phase 205 mostra il carico
      per eguagliare il tonnellaggio solo *dopo* lo switch e solo nella scheda forza
      singola (`equivalenceHint`): mancano il suggerimento per-riga nel picker
      filtrato (serve il target per-set nell'`ExercisePicker` route) e l'equivalenza
      per-membro nel superset. Vedi `CONVENTIONS.md` § Switch exercise.
- [ ] **+ Add set nel superset.** Phase 205 lo aggiunge a forza singola e stretch;
      la catena superset (2–3 membri con `setIndex` condiviso) richiede add/remove
      per-membro in `SupersetViewModel` + UI. Stessa regola: proposta grigia,
      append subito su disco, stime ricalcolate al completamento.
- [ ] **Process-death: timer in corso.** Bozze testi/set/RPE fatte (Phase 151,
      179); i countdown attivi ripartono da capo e il blocco cardio aperto si
      chiude dal disco per disegno — documentato, non dimenticato.

## Ricorrente

- [ ] **Regen baseline** dopo refactor grossi del cold-start o bump dipendenze:
      `scripts/generate-baseline-profile.sh` (mai a mano), poi commit del profilo.
- [ ] **Dipendenze minori/renovate**: bump + regen verification-metadata
      (`CONVENTIONS.md` § Dependency verification) + CI verde.
