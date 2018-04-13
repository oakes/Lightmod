javapackager -deploy -native rpm -outdir package -outfile Lightmod -srcdir target -appclass lightmod.core -name "Lightmod" -title "Lightmod" -Bicon=package/linux/Lightmod.png -BappVersion=$1
javapackager -deploy -native deb -outdir package -outfile Lightmod -srcdir target -appclass lightmod.core -name "Lightmod" -title "Lightmod" -Bicon=package/linux/Lightmod.png -BappVersion=$1
