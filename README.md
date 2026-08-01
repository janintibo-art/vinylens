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
