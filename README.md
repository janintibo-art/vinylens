# VinyLens — recherche de vinyles sur Discogs uniquement

<p align="center"><img src="app/src/main/res/drawable-nodpi/logo_vinylens.png" width="420"></p>

Photo de la pochette → lecture du texte **hors-ligne** sur le téléphone (ML Kit) → requête envoyée **uniquement à `api.discogs.com`**.
Pas de Google Lens, pas de résultats parasites : rien que des pressages Discogs.

## Ce que fait l'app

Deux photos, deux rôles :

| Face | Ce qu'on y lit | Ce que ça donne |
|---|---|---|
| **Recto** (pochette) | les plus gros textes | artiste + titre |
| **Verso** (dos ou étiquette centrale) | n° de catalogue, code-barres | **le pressage exact** |

- Touche un emplacement → Appareil photo ou Galerie
- OCR local (ML Kit) : aucune image ne quitte l'appareil
- Les textes détectés deviennent des étiquettes cliquables ; un clic sur un n° de catalogue le place dans le bon champ
- Recherche en cascade, du plus précis au plus large :
  `code-barres` → `n° de catalogue + artiste/titre` → `n° de catalogue` → `artiste + titre`
- Case **Vinyles uniquement** (paramètre `format=Vinyl` de l'API)
- Résultats : pochette, titre, année, pays, label, n° de catalogue, format → clic = fiche Discogs
- Bouton **+** sur chaque résultat : ajout direct à ta collection ou ta wantlist Discogs
- Coche verte sur les pressages déjà dans ta collection
- Menu ⋮ → **Nouvelle recherche** pour repartir de zéro
- Bouton de secours : ouvrir la même recherche sur le site Discogs

Le verso est facultatif : sans lui, la recherche se fait sur artiste + titre. C'est lui qui
distingue un pressage original d'une réédition.

## Connexion du compte Discogs

L'app utilise un **personal access token**, qui authentifie ton compte Discogs.
Il sert à la fois pour la recherche et pour écrire dans ta collection (pas besoin d'OAuth).

1. https://www.discogs.com/settings/developers → *Generate new token*
2. Dans l'app : menu ⋮ → **Compte Discogs** → coller → *Connecter*
3. L'app appelle `/oauth/identity`, récupère ton pseudo et affiche « Connecté : … »
4. Menu ⋮ → **Dossier de collection** pour choisir le dossier de destination

Le jeton est stocké en local (SharedPreferences) et n'est envoyé qu'à `api.discogs.com`.
Sans jeton, l'app bascule sur le site web Discogs et les fonctions collection sont désactivées.

## Collection et wantlist

Sur chaque résultat, le bouton **+** ouvre :

- **Ajouter à ma collection** → `POST /users/{pseudo}/collection/folders/{dossier}/releases/{id}`
- **Ajouter à ma wantlist** → `PUT /users/{pseudo}/wants/{id}`
- Changer de dossier
- Ouvrir la fiche Discogs

Les 10 premiers résultats sont vérifiés en arrière-plan via
`GET /users/{pseudo}/collection/releases/{id}` : une **coche verte** signale un pressage déjà
possédé, pour éviter de cataloguer deux fois le même disque. Les ajouts restent mémorisés
localement entre deux lancements.

## Compilation de l'APK

Aucun Android Studio nécessaire : le workflow `.github/workflows/build.yml` compile à chaque
`git push` sur `main` et publie l'APK dans **Releases** (téléchargeable directement depuis le téléphone).

## Commandes Termux

```bash
# 1. Outils
pkg update -y && pkg upgrade -y
pkg install -y git unzip gh
termux-setup-storage        # autorise l'accès au stockage (à valider une fois)

# 2. Décompresser
mkdir -p ~/projets && cd ~/projets
unzip ~/storage/downloads/vinylens.zip -d ~/projets
cd ~/projets/vinylens

# 3. Identité git (une seule fois)
git config --global user.name "Ton Nom"
git config --global user.email "toi@exemple.com"

# 4. Dépôt local
git init -b main
git add .
git commit -m "VinyLens : recherche de vinyles sur Discogs"

# 5. Créer le dépôt GitHub et pousser
gh auth login              # HTTPS → Login with a web browser
gh repo create vinylens --public --source=. --remote=origin --push
```

Variante sans `gh` (dépôt créé à la main sur github.com, avec un *Personal Access Token* comme mot de passe) :

```bash
git remote add origin https://github.com/TON_PSEUDO/vinylens.git
git push -u origin main
```

Mises à jour ensuite :

```bash
git add . && git commit -m "maj" && git push
```

## Récupérer l'APK

`https://github.com/TON_PSEUDO/vinylens/releases` → dernier build → fichier `.apk`
→ autoriser l'installation depuis le navigateur → installer.

L'APK est signé avec la clé *debug* : parfait pour un usage perso, pas publiable sur le Play Store.

## Conseils de photo

- Pochette bien à plat, sans reflet ; cadre serré sur le texte
- Pour le verso, viser la zone du **n° de catalogue** (ex. `SHVL 804`) ou le **code-barres**
- L'étiquette centrale du disque marche aussi très bien comme « verso »
- Si trop de résultats : garder l'artiste seul dans le premier champ et le n° dans le second

## Identité visuelle

- Icône de lancement : `mipmap-mdpi` → `mipmap-xxxhdpi` (48 à 192 px), versions carrée et ronde
- Icône adaptative Android 8+ : `mipmap-anydpi-v26/ic_launcher.xml`
  (`drawable-nodpi/ic_launcher_fg.png` dans la zone sûre + `ic_launcher_bg.png` en fond)
- Logo dans la barre du haut : `drawable-nodpi/logo_vinylens.png`, avec une variante
  au lettrage clair dans `drawable-night-nodpi/` pour le mode sombre

## Direction artistique (v2)

- Palette dédiée (`values/colors.xml` + `values-night/`) : papier crème, encre chaude, or #D9990D.
  Elle **remplace intégralement la palette mauve par défaut de Material 3**, y compris
  `colorSurface`, `colorSurfaceVariant` et `colorOutline`, sinon le mauve ressort par endroits.
- Fond texturé : grain de papier tuilable (`drawable-nodpi/paper_grain.png`) posé en
  `android:windowBackground` par-dessus le crème, avec une variante claire en mode nuit.
- Relief : cartes `MaterialCardView` (rayon 16-18 dp, élévation 2-3 dp, filet 1 dp) pour les
  vignettes recto/verso et chaque résultat ; le liseré d'une vignette passe en or quand la face
  est lue.
- Typographie : titres et libellés en serif (Noto Serif système), n° de catalogue en monospace.
- État vide illustré, pastilles numérotées 1 / 2 sur les vignettes, encart doré pour les messages.

## Catalogage en série (v2.3)

- **Mode à la chaîne** (menu ⋮, actif par défaut) : après le recto, l'appareil photo repart seul
  sur le verso ; après chaque ajout à la collection, un nouveau disque démarre.
  Reculer pendant la photo du verso lance la recherche sans lui.
- **Limiteur de débit** dans `DiscogsApi` : 50 requêtes/minute maximum (la limite Discogs est de 60),
  file d'attente automatique plutôt qu'une erreur, et relance en respectant l'en-tête `Retry-After`
  en cas de 429.
- **Vérification de collection** réduite aux 5 premiers résultats, avec cache des pressages
  déjà vérifiés pour ne jamais interroger deux fois le même.
- **N° de catalogue** : trois familles de formats reconnues — anglo-saxon (`SHVL 804`, `PCS 7027`),
  Pathé/EMI (`2C 062-11.653`) et Philips/Polydor/Barclay/Vogue (`6325 022`, `80 502`, `30.123`),
  avec un score de vraisemblance et rejet des années isolées.
- Compteur de disques ajoutés affiché pendant la session.

## Pressages sans code-barres (petits labels)

Les familles de formats servent à **classer les candidats**, pas à filtrer : tout le texte lu
au verso reste proposé en étiquettes, et le champ est libre.

Pour les pressages underground, la cascade tente en plus :

1. le contenu du champ n° envoyé au paramètre `barcode` de l'API, qui interroge **tous les
   identifiants** d'un pressage : matrice / runout gravé dans le dead wax, Label Code, « Other » ;
2. artiste + titre sans le filtre vinyle ;
3. la ligne la plus visible du recto seule — en général le nom du groupe.

Formats courts reconnus : `SR-04`, `TG5`, `TG001`, `AMREP049`, `PBR12`, `DISCHORD 14`, `SST 016`.

## Mode « Étiquettes du disque » (v2.5)

Beaucoup de maxis techno n'ont pas de pochette imprimée : white label, pochette noire ou blanche
générique. Toute l'information est alors sur l'**étiquette centrale** et dans le **dead wax**.

Un sélecteur en haut de l'écran bascule entre :

| Mode | Vignette 1 | Vignette 2 | Traitement |
|---|---|---|---|
| **Pochette** | Recto | Verso | recto = artiste/titre, verso = n° et code-barres |
| **Étiquettes du disque** | Face A | Face B | les deux faces sont fouillées pour l'artiste **et** le n° |

En mode disque, chaque photo est relue par ML Kit sous **quatre orientations** (0°, 90°, 180°, 270°)
et les résultats fusionnés : sur une étiquette, le texte est disposé en cercle et une partie se
retrouve toujours à l'envers. Le décodage passe par `BitmapFactory` + `ExifInterface`, avec
sous-échantillonnage à 2200 px pour tenir en mémoire.

Le choix du mode est mémorisé entre deux lancements.

## Code-barres (v2.6)

Deux chemins, complémentaires :

- **Scanner en direct** — le bouton à droite du champ n° ouvre le scanner des services Google Play
  (`play-services-code-scanner`). Aucune permission caméra à demander : l'aperçu tourne dans le
  processus de Play Services. Formats : EAN-13/8, UPC-A/E, Code 128, Code 39, avec zoom automatique.
  Le module se télécharge au premier usage (connexion requise une fois).
- **Lecture passive** — chaque photo prise est aussi examinée par ML Kit `barcode-scanning`
  (modèle embarqué, hors-ligne). Un code lu par ce biais est plus fiable que sa transcription
  en OCR, et il ne remplace jamais une valeur déjà présente dans le champ.

Le critère `barcode` de la recherche accepte désormais 8 à 14 chiffres, pour couvrir EAN-8 et UPC-A.

## Session de catalogage (v3.0)

| Fonction | Où | Ce que ça fait |
|---|---|---|
| **Mettre de côté** | bouton sous le message | garde le disque non identifié avec ses deux photos et le texte lu |
| **Pas sur Discogs** | bouton sous le message | prépare une fiche (artiste, titre, label, n°, année, format, notes) avec les photos |
| **Journal de session** | menu ⋮ | liste les ajouts, permet d'en **retirer un** de la collection (DELETE de l'exemplaire) |
| **État par défaut** | menu ⋮ | applique un état disque + pochette à chaque ajout via les champs de collection |
| **Export** | dans chaque pile | CSV + photos via la feuille de partage |

Détails d'implémentation :

- Stockage local en JSON (`filesDir/vinylens_items.json`), photos copiées dans `filesDir/photos`
  (le cache pouvant être vidé par le système).
- Le pressage dont le n° de catalogue correspond exactement remonte en tête avec un badge doré :
  comparaison normalisée, `SR-04` = `SR 04` = `sr04`.
- `addToCollection` renvoie l'`instance_id`, indispensable pour annuler un ajout et pour écrire
  l'état sur le bon exemplaire.
- Hors ligne : un ajout part en file d'attente et se rejoue au lancement suivant ; une recherche
  impossible met automatiquement le disque de côté avec ses photos.
- Dead wax : si la première lecture ne donne rien, l'image est repassée avec renfort de contraste
  local (estimation du fond par flou boîte, soustraction, étirement) puis relue dans les 4 sens.

**Création de pressage** : l'API Discogs n'expose aucun endpoint de soumission. Les fiches sont donc
préparées en local et le formulaire du site (`/release/add`) s'ouvre depuis la pile.

## Bibliothèque locale (v3.1)

L'app tient désormais son propre catalogue, indépendant de Discogs.

**Ajout** — sur un résultat, le menu propose « Discogs + ma bibliothèque », « Ma bibliothèque
seulement » ou « Discogs seulement ». Une fiche locale conserve :

- les photos prises pendant la recherche (recopiées, pas de simple référence au cache)
- la pochette téléchargée une fois, donc consultable hors ligne
- le lien Discogs, le n° de catalogue, le label, l'année, le pays, le format
- les **genres et styles** renvoyés par Discogs (`genre` + `style` du résultat de recherche)
- la **caisse de rangement** et des **notes personnelles**

La caisse courante (menu ⋮) est proposée par défaut à chaque ajout : on remplit une caisse,
on la change une fois, et la série suivante suit.

**Écran bibliothèque** — recherche plein texte (artiste, titre, label, caisse, notes, genre),
tri A→Z artiste / A→Z titre / récents, et filtres par caisse et par genre. Le tri alphabétique
ignore la casse et les articles initiaux (« The », « Le », « Les »).

**Fiche du disque** — pochette, identité, genres, rangement, notes, et une galerie de photos
libre : appui long sur une vignette pour la supprimer. Boutons ouvrir sur Discogs, partager
(texte + photos) et supprimer.

Stockage : `filesDir/vinylens_library.json` pour les fiches, `filesDir/library/` pour les images.
Rien ne part sur un serveur.
