.PHONY: apk clean clean-apk install

apk:
	./gradlew assembleRelease

clean:
	./gradlew clean

clean-apk:
	./gradlew clean assembleRelease

install: apk
	adb install -r app/build/outputs/apk/release/app-release.apk
