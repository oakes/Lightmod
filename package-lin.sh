set -e

jpackage --type deb --name Lightmod --app-version $1 --input target --main-jar Lightmod-$1-linux.jar --icon package/linux/Lightmod.png --copyright "Public Domain" --linux-shortcut --linux-app-category Development

jpackage --type app-image --name Lightmod --app-version $1 --input target --main-jar Lightmod-$1-linux.jar --icon package/linux/Lightmod.png --copyright "Public Domain"

echo "#!/bin/sh

cd \"\$(dirname \"\$0\")\"
./bin/Lightmod" >> Lightmod/AppRun

chmod +x Lightmod/AppRun

echo "[Desktop Entry]
Name=Lightmod
Exec=/bin/Lightmod
Icon=/lib/Lightmod
Terminal=false
Type=Application
Categories=Development;" >> Lightmod/lightmod.desktop

appimagetool Lightmod
