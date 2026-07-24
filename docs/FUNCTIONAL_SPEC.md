# Schermata_routine
## Nome routine
In fase di creazione della routine, l'utente può definire il nome della routine
Viene mostrato sopra
## note
In fase di creazione della routine l'utente può definire delle note per la routine.
Anche se in fase di creazione non viene inserito nulla, nella schermata_routine viene mostrato un box testuale vuoto.
Nella schermata_routine il box è sempre modificabile: cliccandoci sopra l'utente può modificare il testo, che viene salvato automaticamente.
## Lista esercizi
In afse di creazione della routine, l'utente definisce quali esercizi includere a partire da una lista.
(Nella schermata della lista di esercizi può creare nuovi esercizi)
Non vorrei che fossero dei pulsanti, ma che fosse chiaro si debba cliccare su ogni esercizio per andare alla schermata_esercizio.
## progresso
Finita tutta la routine (quando un esercizio viene completato l'utente deve segnarlo nella schermata_esercizio) viene mostrato sotto il progresso.
Valuta tu quali grafici e quali dati mostrare.
Mi interessa: peso sollevato, ripetizioni, tonnellaggio. Sia per tutta la routine, che per ogni parte del corpo (in fase di creazione dell'esercizio, l'utente deve selezionare a quale parte del corpo fa riferimento. Le parti del corpo è possibile crearle, ma quando ne sono già state create, l'utente può selezionare da quelle già create). Compara il progresso con il passato.

# Schermata_esercizio_forza
## Immagine / video
In fase di creazione dell'esercizio dalla lista di esercizi, l'utente può inserire il link di un immagine (deve accettare anche link di google drive) oppure di un video YT.
SE l'utente inserisce il link, allora quando apre questa schermata l'utente vede l'immagine oppure il video youtube embedded (è in pausa, ma può essere fatto partire. Di default ha audio mutato).
Le immagini devono essere storate in cache, ottimizzando il retrieve dell'immagine: non va bene caricarle solamente quando l'utente apre questa schermata.
## Spiegazione testuale
In fase di creazione dell'esercizio dalla lista di esercizi, l'utente può inserire una spiegazione testuale di come funziona l'esercizio.
Anche se l'utente non la inserisce in fase di creazione dell'esercizio, un box testuale compare: vuoto se non ha scritto niente l'utente, con il testo della spiegazione se l'ha inserita.
In ogni caso il campo di testo è modificabile anche da questa schermata e viene salvato in autoamtico (senza pulsante "salva")
## Set
In fase di creazione della routine, quando viene inserito l'esercizio nella routine viene chiesto quanti set bisogna fare.
Per ogni set da fare, viene creato una riga con 2 box (come nella schermata): il box a sinistra è per il numero di ripetizioni, il box di destra è per il peso.
In fase di inserimento dell'esercizio nella routine, l'utente deve inserire l'obiettivo di ripetizioni, che sarà un range (es. 8 - 12). Sopra i box delle ripetizioni, viene riportato questo range da ottenere.
All'interno dei box, poco opaco, vengono riportati i set e i pesi eseguiti la volta precedente (nella routine precedente).
Per inserire il numero di ripetizioni e il peso l'utente deve poterlo fare in modo veloce e agile, con una sola mano. Trova un modo facile di fare inserire questi valori: evitare una tastiera a comparsa.
## Grafico del progresso
Solo una volta che l'utente ha inserito qualcosa in tutti i box dei set, allora il grafico del progresso compare.
Valuta tu cosa mostrare, valuta quali grafici utilizzare.
Mi interessano cose come il progresso del peso, ma anche delle ripetizioni, del tonnellaggio... a lungo termine anche.
## Fine esercizio
Un pulsante da premere per tornare alla routine.
Quando viene premuto, nella schermata della routine la riga dell'esercizio completato risulta opaca, oppure spuntata, o magari sbarrata. Valuta te il modo migliore di mostrare che quell'esercizio è stato completato.

# Schermata_esercizio_stretching
## Nome esercizio
(vedi Schermata_esercizio_forza)
## Immagine o YT
(vedi Schermata_esercizio_forza)
## Spiegazione
(vedi Schermata_esercizio_forza)
## Avvia cronometro
Fa partire un cronometro da 00:00
Il cronometro non viene mostrato nell'app, viene mostrato SOLAMENTE nella barra in alto del telefono, dove ci sono i simboli delle notifiche (per capirci: dove c'è il simboletto del wifi, del collegamento al 5G ecc...)
Non ha nessuna funzionalità: semplicemente parte da 0 e progredisce nel tempo. Per fermarlo basta ripremere sul pulsante, che sarà diventato "Ferma cronometro", pulsante ora è diventato rosso.
## Set
In base alle informazioni inserite in fase di creazione dell'esercizio, qui vengono mostrate tante righe quanti set da fare.
Ogni riga mostra il tempo per cui stretchare (definito in fase di creazione dell'esercizio) e a destra un checkbox. Il checkbox è inizialmente vuoto e si clicca per segnare che è completato. Una volta checkato, non può essere uncheckato.
## Fine esercizio
(vedi Schermata_esercizio_forza)

# Schermata_principale
## Week view
Pulsante che porta alla week_view
## Esercizi
Pulsante che porta alla esercizi_list
## Routines
Pulsante che porta alla routine_list
## Gitgraph view
Un grafico simile al gitgraph dei commit
mostra 4 file da 7 quadratini l'una, che rappresentano le 4 settimana precedenti
Il quadratino del giorno attuale è riconoscibile come diverso dagli altri, magari dal bordo (decidi tu)
Ogni quadrato può essere di 3 colori:
- Scuro (nero, grigio, decidi tu) se non è stato registrato nessun allenamento quel giorno
- Verde se il tonnellaggio della routine fatta in quel giorno era supeiore al tonnellaggio della stessa routine fatta la volta precedente
- Rosso se il tonnellaggio della routine fatta in quel giorno era inferiore al tonnellaggio della stessa routine fatta la volta precedente

# Week_view
Presenta i 7 giorni della settimana, con sotto un pulsante per andare alla routine impostata per quel giorno
In fase di creazione della routine, l'utente deve inserire il giorno in cui la routine deve essere eseguita.
nella lista delle routine è possibile disattivare una routine: in quel caso non deve essere mostrata in questa lista.
Non necessariamente ogni giorno avrà una routine.
Quando la week_view viene aperta, viene evidenziato il giorno corrente: decidi tu in che modo.

# esercizi_list
La lista presenta la lista di esercizi creati dall'utente.
La lista è divisa in parti del corpo (inserita dall'utente in fase di creazione dell'esercizio).
Cliccando su un esercizio, l'utente va ad una schermata con la stessa struttura di new_exercise_schermata ma compilata con i valori di quell'esercizio, qui può modificare tutti i valori.
Quando l'utente preme il pulsante "NUOVO ESERCIZIO" (fisso in basso alla schermata) va alla schermata new_exercise_schermata.
Gli esercizi si distinguono visivamente tra "stretch" e "forza". Suggerisco un bordo di colore diverso (rosso > forza, blu > stretch), ma decidi tu quale è il modo migliore.

# new_exercise_schermata
## Nome esercizio
Il suo nome, stringa
## Link
link ad una immagine o video yt
Verrà mostrato quando l'utente aprirà l'esercizio durante una routine
Deve accettare anche link a img drive
NOTA: le immagini dovrebbero essere salvata in cache una volta che viene selezionata la routine, in modo da non doversi caricare ogni volta che l'utente apre l'esercizio
Il video YT deve essere embedded nella schermata dell'esercizio quando l'utente la apre dalla schermata di routine
## Note
Note testuali che l'utente può inserire
## Bodypart
Non so se textbox, menu a tendina, o come. è la sezione in cui l'utente definisce la parte del corpo.
Per definire la parte del corpo l'utente può iniziare a scrivere e gli vengono mostrate le opzioni già esistenti (cioè le body part già inserite in altri esercizi). Può cliccare le opzioni già esistenti per impostare quella oppure crearne una nuova.
## Stertch / forza
Due pulsanti, l'utente deve selezionarne uno.
Di default è "forza"
Selezionare stretch deseleziona forza e viceversa

# routine_list
Per ogni routine creata viene mostrato un pulsante
Quando una routine già creata viene cliccata, va nella new_routine_schermata già compilata dove può modificare i parametri.
Cliccando il pulsante "NUOVA ROUTINE" va nella new_routine_schermata dove può creare una nuova routine.

# new_routine_schermata
è la schermata in cui l'utente può inserire esercizi dalla lista di esercizi. Come l'utente sceglie gli esercizi dalla lista lo lascio decidere a te.
La schermata presenta un esempio di esercizio selezionato.
Selezionato l'esercizio l'utente deve inserire quanti set devono essere fatti
In base al numero di set, vengono mostrate tante righe quanti set.
Se l'esercizio è di tipo "forza" allora per ogni set l'utente deve inserire il range di ripetizioni (es. 8 - 12), se è di tipo "stretch" l'utente deve inserire il tempo per ogni set (es. 60)
