jpackage --type app-image --name Lightmod --app-version %1 --input target --main-jar Lightmod-%1-windows.jar --icon package/windows/Lightmod.ico --copyright "Public Domain"

jpackage --type exe --name Lightmod --app-version %1 --input target --main-jar Lightmod-%1-windows.jar --icon package/windows/Lightmod.ico --copyright "Public Domain" --win-menu --win-per-user-install
