# Wiki Publishing

The GitHub Wiki is initialized at:
https://github.com/RrkwM/Swarmie/wiki

Update the source pages here, then mirror them with a separate wiki checkout:

```bash
git clone https://github.com/RrkwM/Swarmie.wiki.git swarmie-wiki
cp docs/wiki/Home.md swarmie-wiki/Home.md
cp docs/wiki/Player-Guide.md swarmie-wiki/Player-Guide.md
cp docs/wiki/Developer-Guide.md swarmie-wiki/Developer-Guide.md
cp docs/wiki/Installation.md swarmie-wiki/Installation.md
cp docs/wiki/FAQ.md swarmie-wiki/FAQ.md
cd swarmie-wiki
git add .
git commit -m "docs: update player and developer wiki"
git push
```

Canonical, versioned documentation remains in `docs/`; the Wiki is a short
navigation/mirror. Do not maintain long contract/lifecycle text only in Wiki.
