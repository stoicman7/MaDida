# MaDida — application Android d'appel vocal hors ligne

MaDida est une application Android native destinée à une personne qui parle kabyle (variante de Béjaïa) et ne lit pas. Elle sert uniquement à choisir un contact familial par la voix, demander une confirmation, puis lancer l'appel téléphonique.

## Principe de reconnaissance : pas de transcription, pas d'Internet

MaDida ne cherche pas à comprendre une conversation complète ni à convertir le kabyle en texte. Pendant la configuration, la même personne enregistre plusieurs fois le nom de chaque contact. Au moment d'appeler, l'application compare le nouvel enregistrement avec ces exemples locaux à l'aide de caractéristiques acoustiques MFCC et d'un alignement temporel DTW (Dynamic Time Warping).

Cette approche est indépendante de la langue et de son orthographe : elle compare la façon dont la personne prononce le nom. Elle est adaptée à un petit vocabulaire fermé de noms de contacts, mais elle n'est pas un moteur général de reconnaissance vocale et ne doit pas être présentée comme infaillible.

La première version utilise des seuils prudents de correspondance. Ils devront être validés avec la voix réelle de la grand-mère, les noms de sa famille et le téléphone cible. Si le score est faible ou si deux contacts sont trop proches, MaDida demande de répéter au lieu de proposer un appel.

## Fonctionnalités

- Interface Android native en français, avec un grand bouton microphone.
- Nom d'application et identité Android : **MaDida**.
- Nombre de contacts non limité artificiellement par le code.
- Enregistrement d'autant d'exemples de prononciation que nécessaire pour chaque contact.
- Comparaison locale de tous les exemples disponibles ; aucun extrait audio n'est envoyé sur Internet.
- Réponses vocales personnalisées OUI et NON, elles aussi apprises avec plusieurs enregistrements.
- Boutons OUI et NON visibles comme solution de secours.
- Enregistrement de messages parlés en kabyle par la famille.
- Enregistrement audio WAV mono 16 kHz pour garder un format cohérent avec le moteur acoustique.
- Appel lancé uniquement après confirmation. Si l'autorisation d'appel direct est refusée, Android ouvre le composeur téléphonique.
- Aucune permission Internet dans le manifeste Android.

## Limites à connaître

1. **La reconnaissance n'est pas encore validée sur la voix de la grand-mère.** MFCC + DTW est un moteur de comparaison acoustique local, pas un grand modèle neuronal. Des noms qui se ressemblent beaucoup, un bruit important ou une prononciation très différente des échantillons peuvent provoquer une hésitation ou une erreur.
2. Enregistrer au moins **trois exemples propres par contact** est nécessaire pour que le contact soit éligible à la reconnaissance. En pratique, enregistrer 5 à 8 fois avec de petites variations naturelles est préférable.
3. Enregistrer au moins **deux exemples OUI et deux exemples NON** pour activer la confirmation entièrement vocale. Les boutons restent disponibles.
4. Les seuils `MAX_DISTANCE` et `MIN_MARGIN` dans `LocalVoiceMatcher.kt` sont des valeurs de départ. Il faut les calibrer avec des enregistrements positifs et négatifs de cette utilisatrice avant un usage régulier.
5. Les contacts, échantillons et messages audio restent dans le stockage privé de l'application. La désinstallation efface ces données.
6. Ce prototype ne doit pas être l'unique moyen de joindre les services d'urgence.

## Architecture

- `MainActivity.kt` : interface, gestion des états et flux d'appel.
- `LocalData.kt` : stockage local, enregistrement WAV et lecture des messages.
- `LocalVoiceMatcher.kt` : lecture WAV, extraction MFCC, normalisation, DTW et décisions conservatrices.
- `AndroidManifest.xml` : permissions microphone et téléphone uniquement ; aucune permission Internet.

Aucun serveur, compte Google, clé API, backend Python ou service de reconnaissance cloud n'est nécessaire.

## Construire l'APK avec GitHub Actions

1. Crée un dépôt GitHub pour MaDida.
2. Téléverse le contenu de ce dossier à la racine du dépôt.
3. Ouvre l'onglet **Actions** et lance **Build MaDida APK**, ou pousse le code sur `main`.
4. Dans l'exécution réussie, télécharge l'artefact `madida-debug-apk`.
5. Extrais `app-debug.apk` et installe-le sur le téléphone Android.

Le workflow utilise Java 17, Android SDK 35 et Gradle 8.10.2. Android Studio n'est pas nécessaire sur ton ordinateur.

## Première configuration

1. Ouvre **MaDida**, puis **Réglages famille**.
2. Ajoute les membres de la famille avec leur nom et leur numéro.
3. Sélectionne un contact et enregistre au moins trois, idéalement cinq à huit, prononciations du nom par la grand-mère.
4. Enregistre le nom à annoncer pendant la confirmation avec la voix d'un membre de la famille (facultatif mais recommandé).
5. Enregistre au moins deux exemples de sa réponse OUI et deux de sa réponse NON.
6. Enregistre les quatre messages vocaux en kabyle de Béjaïa : demander qui appeler, demander confirmation, demander de répéter et annoncer qu'un nom n'a pas été trouvé.
7. Teste chaque contact plusieurs fois, puis teste aussi des noms qui ne sont PAS dans le répertoire. Vérifie que MaDida demande de répéter dans ces cas.
8. Teste les appels avec un proche avant de confier le téléphone à la grand-mère.

## Appel et sécurité

La reconnaissance seule ne lance jamais d'appel. Le contact reconnu est annoncé, puis l'utilisatrice doit confirmer oralement (si les exemples OUI/NON sont enregistrés) ou toucher le bouton OUI. Le bouton NON annule la sélection.
