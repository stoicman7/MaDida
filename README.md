# MaDida — application Android d'appel vocal hors ligne

MaDida est une application Android native destinée à une personne qui parle kabyle (variante de Béjaïa) et ne lit pas. Elle sert uniquement à choisir un contact familial par la voix, demander une confirmation, puis lancer l'appel téléphonique.

## Principe de reconnaissance : pas de transcription, pas d'Internet

MaDida ne cherche pas à comprendre une conversation complète ni à convertir le kabyle en texte. Pendant la configuration, la même personne enregistre plusieurs fois le nom de chaque contact. Au moment d'appeler, l'application compare le nouvel enregistrement avec ces exemples locaux à l'aide de caractéristiques acoustiques MFCC et d'un alignement temporel DTW (Dynamic Time Warping).

Cette approche est indépendante de la langue et de son orthographe : elle compare la façon dont la personne prononce le nom. Elle est adaptée à un petit vocabulaire fermé de noms de contacts, mais elle n'est pas un moteur général de reconnaissance vocale et ne doit pas être présentée comme infaillible.

MaDida utilise une identification par classement : elle compare l’enregistrement aux exemples de tous les contacts, puis sélectionne celui dont la distance acoustique est la plus faible. Elle ne rejette pas le meilleur candidat parce que son score serait élevé ou parce que deux contacts seraient proches. Le contact choisi est toujours annoncé et l’appel reste bloqué jusqu’à une confirmation explicite.

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

1. **La reconnaissance n'est pas encore validée sur la voix de la grand-mère.** MFCC + DTW classe les contacts par proximité acoustique ; il choisit toujours le meilleur candidat disponible. Si le nom prononcé ne correspond à aucun contact, il choisira malgré tout le moins éloigné : la confirmation humaine est donc indispensable.
2. Enregistrer au moins **un exemple propre pour chaque contact** est nécessaire pour comparer toute la liste. En pratique, enregistrer 5 à 8 fois avec de petites variations naturelles améliore la couverture des prononciations.
3. Enregistrer au moins **deux exemples OUI et deux exemples NON** pour activer la confirmation entièrement vocale. Les boutons restent disponibles.
4. Les seuils de distance ne servent plus à rejeter un nom de contact : le moteur classe les contacts et choisit le plus proche. Les seuils restent utilisés pour distinguer les réponses vocales OUI et NON.
5. Les contacts, échantillons et messages audio restent dans le stockage privé de l'application. La désinstallation efface ces données.
6. Ce prototype ne doit pas être l'unique moyen de joindre les services d'urgence.

## Architecture

- `MainActivity.kt` : interface, gestion des états et flux d'appel.
- `LocalData.kt` : stockage local, enregistrement WAV et lecture des messages.
- `LocalVoiceMatcher.kt` : lecture WAV, extraction MFCC + deltas, DTW et classement du contact le plus proche.
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
3. Enregistre au moins une prononciation pour chacun des contacts ; cinq à huit prononciations par contact sont préférables.
4. Enregistre le nom à annoncer pendant la confirmation avec la voix d'un membre de la famille (facultatif mais recommandé).
5. Enregistre au moins deux exemples de sa réponse OUI et deux de sa réponse NON.
6. Enregistre les quatre messages vocaux en kabyle de Béjaïa : demander qui appeler, demander confirmation, demander de répéter et annoncer qu'un nom n'a pas été trouvé.
7. Utilise le bouton **Tester la reconnaissance (sans appeler)** pour vérifier quel contact est classé en premier. Même un nom absent du répertoire sera rapproché du contact le plus similaire : vérifie toujours le nom annoncé avant de confirmer.
8. Teste les appels avec un proche avant de confier le téléphone à la grand-mère.

## Appel et sécurité

La reconnaissance seule ne lance jamais d'appel. Le contact reconnu est annoncé, puis l'utilisatrice doit confirmer oralement (si les exemples OUI/NON sont enregistrés) ou toucher le bouton OUI. Le bouton NON annule la sélection.
