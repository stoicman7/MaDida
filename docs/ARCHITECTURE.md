# MaDida — architecture hors ligne

## Flux principal

1. `SimpleAudioRecorder` capture de l'audio PCM mono 16 kHz et écrit un WAV local.
2. `LocalVoiceMatcher` lit le WAV, coupe les silences, extrait des MFCC, applique une normalisation cepstrale et compare la séquence courante aux échantillons enregistrés avec Dynamic Time Warping.
3. Pour chaque contact suffisamment entraîné, le moteur agrège les trois distances les plus proches.
4. Le candidat est accepté seulement si son score est inférieur au seuil configuré et suffisamment meilleur que le deuxième candidat.
5. L'application joue le message de confirmation et le nom enregistré, puis attend une réponse.
6. Les réponses OUI/NON sont comparées aux échantillons vocaux correspondants. En cas d'incertitude, l'application ne passe pas l'appel et propose les boutons.
7. `ACTION_CALL` est déclenché après validation explicite ; le composeur est utilisé comme solution de repli si la permission d'appel direct n'est pas accordée.

## Pourquoi ce choix

Le besoin est une classification dans un vocabulaire fermé (noms de proches), et non une dictée ou une conversation. Une transcription ASR pourrait écrire un prénom de façon incorrecte même quand le son est suffisamment proche d'un exemple déjà enregistré. La comparaison acoustique locale évite la dépendance à une langue, à une orthographe, à un réseau et à une API externe.

## Fichiers

- `MainActivity.kt` — UI Compose, contacts, enregistrement, confirmation et appels.
- `LocalData.kt` — `FamilyStore`, `SimpleAudioRecorder`, `PromptPlayer`.
- `LocalVoiceMatcher.kt` — MFCC, DTW, seuils de correspondance et classeur OUI/NON.

## Paramètres à régler après essais réels

Dans `LocalVoiceMatcher.kt` :

- `MAX_DISTANCE` : seuil maximal de distance acoustique.
- `MIN_MARGIN` : écart minimal entre le meilleur et le deuxième contact.
- `MIN_NAME_SAMPLES` : minimum d'échantillons de prononciation pour activer un contact.

Ces paramètres ne sont pas des performances garanties. Les régler avec des essais comprenant de vrais appels demandés, des noms proches, des mots hors répertoire, des répétitions et du bruit ambiant.
