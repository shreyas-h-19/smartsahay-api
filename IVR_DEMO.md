# SmartSahay IVR demo (branch `feature/ivr-demo`)

A simulated voice helpline: an on-screen phone keypad that reads prompts aloud in the browser and calls new API endpoints.
It is a demo only. It is not a real phone number and needs no telephony provider.

## What was added

| Type | File |
|---|---|
| New | `src/main/java/com/smartsahay/servlet/IvrServlet.java` (`/api/ivr/*`) |
| New | `src/main/java/com/smartsahay/service/IvrService.java` (menus, keypad codes to request, text for speech) |
| Edited | `model/EligibilityRequest.java`: added static `of(...)` |
| Edited | `model/EligibilityResult.java`: added `getEligibleCount()` and `getEligible()` |
| New | `smartsahay-frontend/src/components/IvrKeypad.jsx` and `IvrKeypad.css` |
| New | `smartsahay-frontend/src/pages/IvrDemo.jsx` |
| Edited | `smartsahay-frontend/src/App.jsx` (route `/ivr`) and `components/Navbar.jsx` ("Voice Demo" link) |

The edits to existing files only add code, and the existing JSON responses do not change.

## Mode 1: scheme information (two-level menu, built from the database)

1. **Main menu:** 1 = Central government schemes, 2 = Karnataka state schemes (from the `schemes.level` column), 0 = repeat.
2. **Scheme list:** the schemes of that group, 8 per page. Keys 1 to 8 pick a scheme, 9 = more schemes, 0 = main menu.
3. **Summary:** the scheme name, its first benefit and where to apply. Then press another number, or 0 for the main menu.

The page keeps no scheme list of its own. The server builds every menu from the `schemes` table on each request, so adding or removing a scheme
(for example in the admin panel) changes the menu with no code change. With the 17 seeded schemes: Central = 10 (page 1 has 8, page 2 has 2), State = 7.
(Grouping by `category` was rejected: the seed data has 15 categories, most with one scheme, which is more than the keys on a phone.)
Menus are rebuilt on each request, so if an admin edits schemes in the middle of a call the numbering can shift for that call.

## API

```
GET  /api/ivr/menu                              main menu
GET  /api/ivr/menu?group=1&page=0               schemes of group 1 (Central) or 2 (State), page 0, 1, ...
GET  /api/ivr/scheme?group=1&page=0&digits=3    summary of the 3rd scheme on that page
POST /api/ivr/eligibility                       {"state":1,"age":35,"category":2,"acres":3,"income":2,"crop":3}
```
All return `{"gather_prompt":{"text":"..."},"max_input_digits":1}`. Menu responses also have `"level"` (`main` or `list`) and `"options":[{"key":"1","label":"..."}]`.

## Mode 2: eligibility (6 keypad questions)

Keypad codes: state 1 = Karnataka, 2 = other. Category 1..5 = General, SC, ST, OBC, Minority. Crop 1..4 = Kharif, Rabi, Millets, Other.
Income 1..5 = below 1 lakh, 1 to 3, 3 to 6, 6 to 8, above 8 lakh (the upper bound is used for matching). Acres are whole acres (0 = no land).
It reuses `EligibilityService` unchanged, so results should match the `/eligibility` page for the same inputs.
Answers are sent in a POST body and are never logged or stored.

## How to check it

1. Run the database, `db.properties` and Tomcat 10.1 as in `README.md`.
2. `http://localhost:8080/smartsahay/api/health` should report OK.
3. In Command Prompt:
   ```
   curl "http://localhost:8080/smartsahay/api/ivr/menu"
   curl "http://localhost:8080/smartsahay/api/ivr/menu?group=1&page=0"
   curl "http://localhost:8080/smartsahay/api/ivr/menu?group=1&page=1"
   curl "http://localhost:8080/smartsahay/api/ivr/scheme?group=1&page=0&digits=1"
   curl -X POST http://localhost:8080/smartsahay/api/ivr/eligibility -H "Content-Type: application/json" -d "{\"state\":1,\"age\":35,\"category\":2,\"acres\":3,\"income\":2,\"crop\":3}"
   ```
   Expected: the main menu with 2 options; page 0 of Central with 8 schemes plus "More schemes"; page 1 with 2 schemes; a spoken summary of the first Central scheme; and a sentence listing up to three schemes.
4. `cd smartsahay-frontend`, then `npm install`, then `npm run dev`, then open `http://localhost:5173/ivr`.
   Try the Central and State lists, key 9 for more, key 0 to go back, an invalid key, then the eligibility mode.
   Compare the same profile with the `/eligibility` page.
5. Check the Tomcat log: it must not show any farmer answers.

## Status: please read

- Checked outside the real app: the menu and eligibility logic ran against these model classes (10 Central and 7 State sample schemes), `IvrServlet.java` compiled against stand-ins for the Jakarta and Gson libraries, and the keypad page ran in a simulated browser with a fake backend.
- **Not yet built or run in the real project against MySQL and Tomcat.** Run `mvn clean package` first and report any compile error.
- Browser speech depends on the device and browser (Chrome or Edge recommended). A transcript is also shown under the keypad.
- This backend ignores the "FRUITS ID" and "Aadhaar-linked bank" questions because its rules do not use them, so the keypad asks only 6 questions.
- On the last page of a group there is no key for "previous page"; press 0 and choose the group again.
- Results are indicative only, not an official eligibility decision.
