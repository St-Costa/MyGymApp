# Piano per velocizzare il backup al server

## Sintesi

Il backup funziona e gli ultimi dati raccolti dal telefono non mostrano errori, ma il
percorso può fare lavoro duplicato. La priorità è evitare l'accodamento/sovrapposizione
di più drain per la stessa coda, poi misurare separatamente client, rete e server.

## Evidenze raccolte dal telefono

Diagnostica scaricata in `artifacts/phone-backup-diagnostics/` il 2026-09-04.

- `state.yml`: 6 sessioni SENT, ciascuna con un solo tentativo; 3.6–4.8 KB e 244–413 ms.
- `readiness_state.yml`: 5/5 SENT, un tentativo ciascuna.
- `scale_state.yml`: 7/7 SENT, un tentativo ciascuna.
- `ecg_state.yml`: 5/5 SENT, un tentativo ciascuna; ECG tra circa 0.69 e 1.77 MB.
- `repo_state.yml`: 68/68 file SENT, 67 con un tentativo e nessun errore.
- `app.log`: tutte le verifiche recenti risultano `verify OK`; l'ultima è `55/55 sessioni allineate`.
- Le durate registrate per le sessioni sono basse e stabili per un singolo POST. Il log mostra
  però lo stesso file repo inviato più volte in pochi secondi/minuti, ad esempio
  `45-oblique-raise`, `wrist-flexion-cable` e `fixed-daily-exercise`.

Conclusione: non c'è evidenza di retry dovuti a failure del server. C'è evidenza di richieste
ridondanti o di più trigger che lavorano sulla stessa coda.

## Interventi lato app

### 1. Serializzare il drain di ogni coda

`RepoSyncWorker.Scheduler.runExpedited()` usava `ExistingWorkPolicy.REPLACE`. Ora i trigger
ordinari usano `KEEP`, mentre quelli forzati usano `APPEND_OR_REPLACE`: un nuovo salvataggio
non cancella più un worker già partito e una richiesta forzata viene eseguita dopo quella in corso.

È stata applicata una sola esecuzione ordinaria per coda e il worker repo usa un mutex condiviso
con il verifier. Il worker deve inoltre fare in modo che
il worker svuoti la coda fino a quando non ci sono più elementi. Un nuovo elemento arrivato
durante il drain deve provocare un secondo passaggio dopo il completamento, non una seconda
esecuzione concorrente.

Applicare la stessa regola alle cinque code, soprattutto quando un salvataggio di sessione
attiva contemporaneamente sessione, readiness, bilancia, ECG e repo.

### 2. Non far sovrapporre `BackupVerifier` e worker

La verifica di fine sessione e il worker potevano vedere gli stessi file PENDING. Ora entrambe
le operazioni acquisiscono `RepoSyncCoordinator`; la verifica attende quindi il drain repo in
corso prima di leggere manifest e ledger. La verifica deve inoltre:

1. attendere il completamento del drain già in corso, oppure acquisire lo stesso lock;
2. rileggere il ledger dopo l'attesa;
3. inviare solo ciò che resta davvero da inviare;
4. eseguire il read-back senza ripubblicare file già confermati.

In alternativa, il verifier può diventare un'operazione solo diagnostica quando il worker ha
già confermato tutti gli elementi.

### 3. Metriche di batch

`RepoSyncWorker` registra già `runId`, numero di elementi, byte, durata totale e failure. Per ogni
esecuzione completa è inoltre utile registrare numero di chunk, durata HTTP e risultato
(`stored`, `duplicate`, `error`). Oggi il log
chunk, durata totale, durata HTTP e risultato (`stored`, `duplicate`, `error`). Oggi il log
per-file non permette di distinguere chiaramente un bulk request da più request separate.

Obiettivo di accettazione: dopo un backfill, un solo run, un solo bulk request per chunk e zero
duplicati dello stesso `runId`.

### 4. Condividere il client HTTP — implementato

Le API usano ora pool OkHttp singleton distinti solo per profilo di timeout (`standard`, `bulk`,
`restore`, `ecg`). Questo riusa le connessioni Tailscale/TLS senza alterare timeout o formato
delle richieste.

## Interventi consigliati lato server (da applicare manualmente)

### 1. Endpoint bulk realmente atomico e senza lavoro inutile

Per `/v1/repo/bulk`, validare tutti gli elementi, scrivere i file con replace atomico e fare un
solo commit git per chunk. Il commit deve essere saltato se tutti gli elementi sono duplicate.
Il parsing SQL non deve stare sul percorso critico dell'HTTP: accodarlo a un job locale dopo la
risposta, se la vista SQL non è necessaria per confermare il backup.

### 2. Lock unico e commit git economico

Usare un lock process-wide attorno a write + `git add` + `git commit`, senza `git gc` o `repack`
nel percorso della richiesta. Per i normali upload di sessione, mantenere un commit per richiesta
solo se è un requisito di audit; per un eventuale endpoint bulk dei cinque tipi, un commit per
batch riduce molto il costo del backfill.

### 3. Misurare il server con tempi separati

Loggare per richiesta: timestamp ingresso, autenticazione, parsing multipart, hash SHA-256,
scrittura/fsync, parsing applicativo, attesa lock git, `git add`, `git commit`, risposta e byte.
Senza questa scomposizione non conviene aumentare timeout o introdurre parallelismo server-side.

### 4. Evitare duplicati senza rifare lavoro

Per hash già presenti, rispondere `duplicate` prima di scrivere, parsare o committare. Per bulk,
restituire un risultato per elemento, ma non creare un commit quando il batch non cambia lo store.

### 5. Tuning infrastrutturale solo dopo la misura

Verificare keep-alive HTTP/2, DNS/MagicDNS e latenza Tailscale tra telefono e server. Non usare
compressione ulteriore per i piccoli Markdown; per ECG mantenere gzip. Non introdurre proxy o
cache che possano compromettere l'autenticità del backup.

## Ordine di esecuzione

1. ~~Aggiungere `runId` e metriche.~~ Fatto per il repo bulk.
2. ~~Eliminare `REPLACE`/sovrapposizioni con un coordinatore unico del drain.~~ Fatto.
3. ~~Serializzare verifier e worker.~~ Fatto per repo/verifier.
4. Testare un backfill controllato e confrontare richieste, byte, commit e tempo totale.
5. Applicare sul server il fast-path duplicate, parsing fuori dal percorso critico e logging
   temporale.
6. ~~Condividere OkHttpClient.~~ Fatto. Ottimizzare Tailscale/HTTP solo se rimane latenza
   significativa dopo le misure.

## Criteri di successo

- Nessun file inviato più di una volta per lo stesso contenuto durante un singolo drain.
- Nessun `duplicate` prodotto da due worker concorrenti dell'app.
- Tutti i ledger restano `SENT`, senza aumento di errori o perdita della semantica idempotente.
- Backfill misurato con un numero di round-trip vicino al numero di chunk, non al numero di file.
- Tempo end-to-end e tempo server riportati separatamente prima e dopo la modifica.
