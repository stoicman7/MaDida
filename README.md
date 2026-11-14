# MaDida — application Android d'appel vocal hors ligne

MaDida est une application Android native destinée à une personne qui parle kabyle (variante de Béjaïa) et ne lit pas. Elle sert uniquement à choisir un contact familial par la voix, demander une confirmation, puis lancer l'appel téléphonique.

## Principe de reconnaissance : pas de transcription, pas d'Internet

MaDida ne cherche pas à comprendre une conversation complète ni à convertir le kabyle en texte. Pendant la configuration, la même personne enregistre plusieurs fois le nom de chaque contact. Au moment d'appeler, l'application compare le nouvel enregistrement avec ces exemples locaux à l'aide de caractéristiques acoustiques MFCC et d'un alignement temporel DTW (Dynamic Time Warping).

Cette approche est indépendante de la langue et de son orthographe : elle compare la façon dont la personne prononce le nom. Elle est adaptée à un petit vocabulaire fermé de noms de contacts, mais elle n'est pas un moteur général de reconnaissance vocale et ne doit pas être présentée comme infaillible.

MaDida classe les contacts par distance acoustique et dispose maintenant d'un garde-fou contre les sons inconnus. Pour chaque contact, elle compare les distances entre ses propres exemples afin d'estimer la variabilité naturelle de sa prononciation. Si le nouveau son est trop éloigné du profil du meilleur candidat, aucun contact n'est proposé. Cette limite est adaptative et doit encore être vérifiée avec la voix réelle. Le contact retenu est toujours annoncé et l'appel reste bloqué jusqu'à une confirmation explicite.

## Fonctionnalités

- Interface Android native en français, avec un grand bouton microphone.
- Nom d'application et identité Android : **MaDida**.
- Nombre de contacts non limité artificiellement par le code.
- Suppression d'un contact avec confirmation et suppression de ses enregistrements vocaux associés.
- Suppression indépendante de chaque prononciation, des audios d'annonce, des réponses OUI/NON et des messages d'instruction.
- Enregistrement d'autant d'exemples de prononciation que nécessaire pour chaque contact.
- Comparaison locale de tous les exemples disponibles ; aucun extrait audio n'est envoyé sur Internet.
- Réponses vocales personnalisées OUI et NON, elles aussi apprises avec plusieurs enregistrements.
- Boutons OUI et NON visibles comme solution de secours.
- Enregistrement de messages parlés en kabyle par la famille.
- Enregistrement audio WAV mono 16 kHz pour garder un format cohérent avec le moteur acoustique.
- Appel lancé uniquement après confirmation. Si l'autorisation d'appel direct est refusée, Android ouvre le composeur téléphonique.
- Aucune permission Internet dans le manifeste Android.

## Limites à connaître

1. **La reconnaissance n'est pas encore validée sur la voix de la grand-mère.** MFCC + DTW combine le classement des contacts et un garde-fou adaptatif construit à partir des exemples enregistrés. Un son trop éloigné des prononciations connues doit être rejeté ; les seuils restent à valider avec des essais positifs et des sons volontairement incorrects.
2. Enregistrer au moins **trois exemples valides pour chaque contact** est nécessaire pour activer le garde-fou et comparer toute la liste. En pratique, enregistrer 5 à 8 fois avec de petites variations naturelles est préférable.
3. Enregistrer au moins **deux exemples OUI et deux exemples NON** pour activer la confirmation entièrement vocale. Les boutons restent disponibles.
4. La limite de rejet des sons inconnus est calculée séparément pour chaque contact à partir des distances entre ses propres exemples (médiane et dispersion robuste). Ce calcul est une heuristique à tester, pas une garantie mathématique.
5. Les contacts, échantillons et messages audio restent dans le stockage privé de l'application. Chaque audio peut être supprimé indépendamment ; supprimer un contact efface aussi ses audios associés. La désinstallation efface toutes les données.
6. Ce prototype ne doit pas être l'unique moyen de joindre les services d'urgence.

## Architecture

- `MainActivity.kt` : interface, gestion des états et flux d'appel.
- `LocalData.kt` : stockage local, enregistrement WAV et lecture des messages.
- `LocalVoiceMatcher.kt` : lecture WAV, extraction MFCC + deltas, DTW, calibration par contact et rejet des sons inconnus.
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
3. Enregistre au moins trois prononciations valides pour chacun des contacts ; cinq à huit sont préférables pour stabiliser le garde-fou.
4. Enregistre le nom à annoncer pendant la confirmation avec la voix d'un membre de la famille (facultatif mais recommandé).
5. Enregistre au moins deux exemples de sa réponse OUI et deux de sa réponse NON.
6. Enregistre les quatre messages vocaux en kabyle de Béjaïa : demander qui appeler, demander confirmation, demander de répéter et annoncer qu'un nom n'a pas été trouvé.
7. Utilise le bouton **Tester la reconnaissance (sans appeler)** pour vérifier les noms connus et essayer volontairement des sons qui ne correspondent à aucun contact. Ces sons doivent être rejetés. Vérifie toujours le nom annoncé avant de confirmer.
8. Teste les appels avec un proche avant de confier le téléphone à la grand-mère.

## Appel et sécurité

La reconnaissance seule ne lance jamais d'appel. Le contact reconnu est annoncé, puis l'utilisatrice doit confirmer oralement (si les exemples OUI/NON sont enregistrés) ou toucher le bouton OUI. Le bouton NON annule la sélection.
