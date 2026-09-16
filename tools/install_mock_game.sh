cat << 'APP_EOF' > /tmp/AndroidManifest.xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dts.freefireth">
    <application android:label="Free Fire" android:hasCode="false">
        <activity android:name="com.dts.freefireth.FFActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
APP_EOF
