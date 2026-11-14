# Stemit Privacy Summary

- Audio is selected by the user through Android's system document picker.
- The separation workflow downloads the model from its configured upstream host on first use.
- After the model is installed, inference is performed locally on the device. Stemit's separation code does not upload the selected audio to a Stemit server.
- Saved Library audio and edited exports are stored in the app's private files directory.
- The user can share a file only by choosing a share target in Android's Sharesheet.
- The optional notification permission is requested for notification functionality; core audio selection and separation do not require broad storage permission.
- The update checker contacts the public GitHub Releases API to check for a published version.
- Uninstalling the app removes its private app files.

This summary describes the current app implementation and is not a substitute for a jurisdiction-specific legal review.