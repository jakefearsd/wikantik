
Here's a brief help on how to login to **[{$applicationname}]**. This text is [here](LoginHelp).   

You can read more about Wikantik's security features on the [documentation pages](Doc:Security).

### Login

Wikantik supports multiple levels of **authentication** and trust. Users can be anonymous, have "asserted" identities using cookies, be authenticated, or be administrators:

| Status | Description | The User greeting Shows..
| Anonymous | User not logged in, and has not supplied a cookie | "G'day (anonymous guest)"
| Asserted | User's browser contains a cookie called `~WikantikAssertedName` | "G'day, _username_ (not logged in)"
| Authenticated | User logged in with a login id and password | "G'day, _username_ (authenticated)"

Depending on the default security policy and page access controls in place, users may (or may not) be required to authenticate.

When a user decides to log in - or is challenged to do so by a page access control or security policy - he or she sees a standard web form with a username field and a masked password field. After receiving the submitted web form, Wikantik attempts to log the user in.

### Lost password

If you lose your password, you can ask to have a new, random password sent to the mail address stored in your User Profile.

### Register new user

Although some wikis are anonymous, many are not. Often, wikis give users the ability to create an identity for the website. Wikantik includes a basic self-registration page that allows users to set up and manage their own wiki profiles.

By default, the form asks for:

* A user ID
* A password
* The user's desired "wiki name" (e.g., [JanneJalkanen]()). You can use this name in Access Control Lists or Wiki Groups
* E-mail address. This can be used when you need to reset your password. (see Lost Password)

If container-managed authentication is used, the user ID will not be editable; the container will supply this value.

When the user saves the profile, Wikantik checks to make sure that the new user id, wiki name and full name aren't already used by someone else. If so, the user is given the opportunity to choose different values.

After a user creates a wiki profile, he or she may edit it at a later time via the _My Prefs_ link. By default, users must be authenticated to edit their own profiles.
