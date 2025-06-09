# 🔑 MFKeysDecoder

![MFKeysDecoder Logo](https://raw.githubusercontent.com/DREAMSOLDIERc404/MFKeysDecoder/main/assets/logo.png) <!-- Sostituisci con il tuo logo o un'immagine rappresentativa -->

## 🚀 Decodifica Chiavi Mifare in modo semplice!

MFKeysDecoder è uno strumento open source progettato per decodificare facilmente l'algoritmo per le chiavi delle card Mifare Classic. Perfetto per hacker, maker, appassionati di sicurezza e per chi vuole esplorare il mondo delle smartcard contactless.

Attraverso un’interfaccia semplice e algoritmi ottimizzati, puoi fare un reverse enginering delle chiavi a partire da almeno 2 dump.

---

## ✨ Caratteristiche principali

- 🔥 **Decodifica rapida** delle chiavi Mifare Classic
- 🖥️ **Interfaccia semplice** e intuitiva da linea di comando (CLI)
- 📚 **Supporto a diversi formati** di input (log, dump, stringhe, ecc.)
- ⚡ **Algoritmi ottimizzati** per ricerca veloce
- 🧑‍💻 **Completamente open source**
- 🏹 Ideale per CTF, pentest, ricerca e didattica

---

## 🖼️ Screenshot

![Screenshot esempio](https://raw.githubusercontent.com/DREAMSOLDIERc404/MFKeysDecoder/main/assets/screenshot1.png)
![Esecuzione tool](https://raw.githubusercontent.com/DREAMSOLDIERc404/MFKeysDecoder/main/assets/screenshot2.png)

---

## 📦 Installazione

1. **Clona il repository**
   ```bash
   git clone https://github.com/DREAMSOLDIERc404/MFKeysDecoder.git
   cd MFKeysDecoder
   ```

2. **Installa Java:**
   ```bash
   /*Google è tuo amico XD*/
   ```

3. **Esegui il tool:**
   ```bash
   ./gradlew build
   ./gradlew run
   ```

---

## ⚙️ Utilizzo

Lancia il programma con il file di log/dump ottenuto dal tuo attacco (DarkSide, Nested, ecc):

```bash
./gradlew run
```

---

## 💡 Esempio di Output

```plaintext
!!!DA INSERIRE!!!
```

---

## 🤝 Contribuisci

Vuoi aggiungere una funzionalità o correggere un bug?  
Le pull request sono benvenute!  
Dai un’occhiata alla sezione [Issues](https://github.com/DREAMSOLDIERc404/MFKeysDecoder/issues) per trovare spunti o segnalare problemi.

---

## 🧑‍💻 Autore

- **DREAMSOLDIERc404**  
  [GitHub](https://github.com/DREAMSOLDIERc404)

---

## 📝 Licenza

Questo progetto è distribuito sotto licenza MIT.  
Sentiti libero di utilizzarlo, modificarlo e condividerlo!

---

> ⚠️ **Attenzione:** Questo software è pensato a scopo didattico e di ricerca.  
> L’uso improprio è a tuo rischio e pericolo.  
> Rispetta sempre la legge e la privacy altrui.

---

## DA AGGIUNGERE

- Aggiungi UIDScramble
- Aggiusta la percentuale, non deve andare in overflow
  - Metti a posto brach di find xor con i dump lungho 
- Salva i candidati su file sia per XORFINDER che per BYTESCRAMBLER
- Meccanica di salvataggio progresso sennò uno dopo 2 ore di attesa magari chiude  e perde tutto.
- Aggiungere un ETA al loading ("Estimated Time of Arrival", ovvero "Orario di arrivo previsto")
- Aggiungere uno scroll se no i dump con più di 20 righe non si leggono (o pensa a un modo perchè si vedano
 bene senza scroll)
- Possbilità di selezionare la lunghezza delle righe (default=16 ma puo cambiare)
- tasto indietro per tornare alla selezione degli algoritmi
- Controllo dei dump per vedere che siano gli stessi e che abbiano stessa lunghezza

---

![MFKeysDecoder Banner](https://raw.githubusercontent.com/DREAMSOLDIERc404/MFKeysDecoder/main/assets/banner.png)
