// The page's words in both languages (index.html carries the Ukrainian ones statically; tests/text-check.py
// keeps them equal), the language and theme switches, and the screenshot viewer.
const REPO = "https://github.com/mystiqpilot/star-nav";
const translations = {
    uk: {
        title: "Star Nav — GPS, якому телефон може довіряти",
        description:
            "Star Nav — застосунок для Android з відкритим кодом: положення антени Starlink як GPS телефона, коли GNSS глушать чи підміняють.",
        theme: "Тема",
        system: "Як у системі",
        light: "Світла тема",
        dark: "Темна тема",
        eyebrow: "Застосунок для Android · відкритий код, GPL-3.0",
        tagline: "GPS, якому телефон може довіряти, коли супутникову навігацію глушать або підміняють.",
        intro:
            "Коли GNSS глушать або підміняють, GPS у телефоні зависає, «пливе» або перестрибує на інший континент, а за ним і всі навігаційні застосунки. Антена Starlink на даху авто при цьому знає, де вона є. Star Nav читає положення антени через її локальну мережу й віддає його всім застосункам на телефоні як GPS Android — через механізм фіктивного місцезнаходження.",
        source: "Код на GitHub",
        howLink: "Як це працює",
        noBuilds: "Готових збірок ще не опубліковано. Застосунок можна зібрати з коду:",
        noBuildsLink: "як це зробити",
        buildHref: `${REPO}/blob/main/README.uk.md#збирання-з-коду`,
        heroAlt: "Сторінка «Позиція»: позиція антени Starlink і GPS телефона",
        screensTitle: "Екрани",
        screensLead:
            "Унизу п'ять сторінок: Позиція, Імітація, Starlink, Сесії й Параметри. Знімки зроблено на емуляторі з тестовою антеною.",
        shotPosition: "Позиція",
        shotPositionText: "Позиція антени й GPS телефона, кожна зі своїм станом",
        shotMock: "Імітація",
        shotMockText: "Що таке фіктивний GPS і як його ввімкнути",
        shotStarlink: "Starlink",
        shotStarlinkText: "Стан антени, зв'язок і оновлення",
        shotSky: "Небо",
        shotSkyText: "Карта перешкод над антеною",
        shotRouter: "Роутер",
        shotRouterText: "Мережі Wi-Fi роутера, пароль прихований",
        shotSettings: "Параметри",
        shotSettingsText: "Тема, запис сесій і як довго їх зберігати",
        howTitle: "Як це працює",
        step1: "Антена на даху",
        step1Text: "Антена Starlink на даху авто знає, де вона є, навіть коли GPS у телефоні зависає чи перестрибує.",
        step2: "Її локальна мережа",
        step2Text:
            "Телефон під'єднаний до Wi-Fi антени. Star Nav читає положення антени з її локального API за адресою 192.168.100.1.",
        step3: "Корекція на телефоні",
        step3Text:
            "Датчики руху телефона й курс антени тримають авто на траєкторії, коли позиція антени «пливе» вбік, і ведуть позицію далі під час збоїв антени.",
        step4: "Кожен застосунок",
        step4Text:
            "Позиція надходить усім застосункам на телефоні як GPS Android — через механізм фіктивного місцезнаходження.",
        featuresTitle: "Що вміє",
        fGps: "Положення антени як GPS телефона",
        fGpsText:
            "Передає найкраще положення антени в Android як провайдер GPS, зсунуте вперед за курсом на свій вік, щоб не відставати на трасі.",
        fInertial: "Інерціальна корекція",
        fInertialText:
            "У режимі Авто тримає авто на траєкторії, коли позиція антени зсувається вбік, а під час збоїв антени веде позицію далі численням шляху й чесно каже застосункам, наскільки вона ще точна. Обчислюється лише на телефоні.",
        fModes: "Один перемикач, три режими",
        fModesText:
            "Авто (рекомендовано), Сирий (позиція антени як є) і Лише Starlink (джерело антени без GNSS). Також з плитки швидких налаштувань і з постійного сповіщення.",
        fCutoff: "Відсічки й перезапуски",
        fCutoffText:
            "Помічає, коли антена вимикає місцезнаходження, бо авто «рухається занадто швидко для тарифу», і коли антена перезапускається та чому. Про відсічку ще й тихо сповіщає.",
        fChecklist: "Перелік налаштувань",
        fChecklistText:
            "Усе, що потрібно телефону: дозвіл на місцезнаходження, параметри розробника, програма для фіктивного місцезнаходження, батарея, Wi-Fi, доступ до місцезнаходження антени. Кожен пункт зі станом і кнопкою до потрібної сторінки налаштувань.",
        fRecorder: "Запис і звіти про точність",
        fRecorderText:
            "Записує відповіді антени, GNSS телефона й датчики руху на телефоні. Кожен запис має звіт: трек, розфарбований за похибкою, відсічки, завади й швидкість. Один перемикач повністю вимикає запис.",
        fExport: "Приватний експорт",
        fExportText:
            "Ви самі обираєте початок і кінець того, що покине телефон; ідентифікатори антени й роутера видаляються.",
        fStarlink: "Антена й роутер",
        fStarlinkText:
            "Усе, що пропонує локальний API антени, простими словами: стан, сповіщення, графіки, карта перешкод, керування, а також стан роутера, пристрої й налаштування Wi-Fi. Кожна зміна спершу питає.",
        fLook: "Світла й темна тема, дві мови",
        fLookText: "Іде за темою Android або за вашим вибором. Українська й англійська, готовий до інших мов.",
        reqTitle: "Вимоги",
        req1: "Android 8.0 або новіший.",
        req2: "Антена Starlink, що повідомляє своє місцезнаходження в локальній мережі. Перевірено на Gen 3 Mini з тарифом Priority; інші антени можуть працювати, але не перевірені.",
        req3: "Телефон під'єднаний до Wi-Fi антени (антена відповідає за адресою 192.168.100.1).",
        req4: "Увімкнені параметри розробника, і Star Nav обрано програмою для фіктивного місцезнаходження; сторінка «Імітація» в застосунку проведе вас крок за кроком.",
        privacyTitle: "Приватність",
        privacy1:
            "Жодних облікових записів, аналітики, реклами чи звітів про збої. Застосунок спілкується з антеною та роутером у вашій локальній мережі; тайли мап завантажує лише після того, як ви оберете підкладку, і тоді сервер тайлів бачить місцевість на екрані. Записи лишаються в приватному сховищі застосунку, доки ви самі не експортуєте частину запису.",
        privacy2:
            "Єдиний виняток, вимкнений, доки ви його не натиснете: якщо антена відмовляється вмикати «місцезнаходження в локальній мережі» з телефона, Star Nav може один раз провести вас у ваш власний обліковий запис Starlink на сторінці Starlink, щоб Starlink це ввімкнув; потім нічого не зберігається. Starlink не публікує для цього API (воно може перестати працювати, а умови Starlink можуть цього не дозволяти), і це на ваш власний ризик.",
        privacyLink: "Повна політика приватності (англійською)",
        privacyPage: "Ця сторінка теж нічого не відстежує: без аналітики, сторонніх скриптів і шрифтів. Лише захист Cloudflare від ботів може ставити свій технічний файл cookie.",
        safetyTitle: "Безпека",
        safety: "Використовуйте як допомогу, а не як єдиний засіб навігації. Положення антени може помилятися на десятки метрів при різкому гальмуванні чи під завадами і зникає під час відсічок за швидкістю; числення шляху інерціальної корекції з кожною секундою менш точне. Не керуйте застосунком під час водіння.",
        creditsTitle: "Подяки",
        creditStarDebug:
            "від ertong — інструмент для Starlink з відкритим кодом, створений для волонтерської ініціативи «Народний Starlink», — надихнув цей застосунок: багато з того, що Star Nav показує й робить з антеною та роутером, зроблено за його прикладом. Дякуємо, що розвиваєте його відкрито.",
        creditGrpc1: "Номери полів локального gRPC API антени взято з дампів рефлексії, опублікованих",
        creditGrpc2: ", та з документації",
        creditMore: "Решта подяк і ліцензій — у",
        creditsHref: `${REPO}/blob/main/README.uk.md#подяки`,
        affiliation:
            "Star Nav — незалежний проєкт із відкритим кодом. Він не пов'язаний зі SpaceX, не схвалений і не підтримується нею. Starlink — торговельна марка Space Exploration Technologies Corp.",
        license: "GPL-3.0 або новіша",
        footerSource: "Код на GitHub",
        previous: "Попередній знімок",
        next: "Наступний знімок",
        close: "Закрити",
        notFound: "Сторінку не знайдено",
        notFoundBody: "Такої адреси тут немає.",
        backHome: "На головну",
    },
    en: {
        title: "Star Nav — a GPS your phone can trust",
        description:
            "Star Nav, an open-source Android app: a Starlink dish's position as your phone's GPS when GNSS is jammed or spoofed.",
        theme: "Theme",
        system: "Same as the system",
        light: "Light theme",
        dark: "Dark theme",
        eyebrow: "Android app · open source, GPL-3.0",
        tagline: "A GPS your phone can trust when satellite navigation is jammed or spoofed.",
        intro: "When GNSS is jammed or spoofed, a phone's GPS freezes, drifts or jumps to another continent, and every navigation app follows it. A Starlink dish on the car roof keeps knowing where it is. Star Nav reads the dish's position over its local network and serves it to all apps on the phone as Android's GPS, using Android's mock location provider.",
        source: "Source on GitHub",
        howLink: "How it works",
        noBuilds: "No release builds are published yet. You can build the app from its source:",
        noBuildsLink: "how to",
        buildHref: `${REPO}#build-from-source`,
        heroAlt: "The Position page: the Starlink dish's position and the phone's GPS",
        screensTitle: "Screens",
        screensLead:
            "Five pages in the bottom bar: Position, Mock GPS, Starlink, Sessions and Settings. Screenshots taken on the emulator with a test dish.",
        shotPosition: "Position",
        shotPositionText: "The dish's position and the phone's GPS, each with its state",
        shotMock: "Mock GPS",
        shotMockText: "What the mock GPS is and how to enable it",
        shotStarlink: "Starlink",
        shotStarlinkText: "The dish's state, connection and software update",
        shotSky: "Sky",
        shotSkyText: "The obstruction map above the dish",
        shotRouter: "Router",
        shotRouterText: "The router's Wi-Fi networks, the password masked",
        shotSettings: "Settings",
        shotSettingsText: "Theme, session logging and how long recordings are kept",
        howTitle: "How it works",
        step1: "Dish on the roof",
        step1Text:
            "A Starlink dish on the car roof keeps knowing where it is, even while the phone's GPS freezes or jumps.",
        step2: "Its local network",
        step2Text:
            "The phone is on the dish's Wi-Fi. Star Nav reads the dish's position from its local API at 192.168.100.1.",
        step3: "Correction on the phone",
        step3Text:
            "The phone's motion sensors and the dish heading hold the car on its track when the dish position drifts off to the side, and carry the position on through dish outages.",
        step4: "Every app",
        step4Text:
            "Every app on the phone gets the position as Android's GPS, through Android's mock location provider.",
        featuresTitle: "What it does",
        fGps: "Dish position as the phone's GPS",
        fGpsText:
            "Passes the dish's best position to Android as the GPS provider, moved forward along the course by its age so it keeps up at highway speed.",
        fInertial: "Inertial correction",
        fInertialText:
            "In Auto mode it holds the car on its track when the dish position drifts sideways, and carries the position on by dead reckoning through dish outages, telling apps honestly how accurate it still is. Computed on the phone only.",
        fModes: "One switch, three modes",
        fModesText:
            "Auto (recommended), Raw (the dish's position as reported) and Starlink only (the dish's GNSS-free source). Also from a Quick Settings tile and the permanent notification.",
        fCutoff: "Cut-offs and restarts",
        fCutoffText:
            "Notices when the dish switches its location off because the car is “moving too fast for the plan”, and when the dish restarts and why. A cut-off also gets a silent heads-up alert.",
        fChecklist: "Setup checklist",
        fChecklistText:
            "Every setting the phone needs: location permission, developer options, mock location app, battery, Wi-Fi, dish location access. Each with its current state and a button to the exact settings page.",
        fRecorder: "Recorder and accuracy reports",
        fRecorderText:
            "Records the dish's answers, the phone's GNSS and its motion sensors on the phone. Each recording gets a report: the track coloured by error, cut-offs, interference and speed. One switch turns logging off completely.",
        fExport: "Private export",
        fExportText: "You choose the start and end of what leaves the phone; dish and router identifiers are removed.",
        fStarlink: "Dish and router",
        fStarlinkText:
            "Everything the dish's local API offers, in plain words: state, alerts, charts, obstruction map, controls, and the router's status, devices and Wi-Fi settings. Every change asks first.",
        fLook: "Light and dark theme, two languages",
        fLookText: "Follows Android's theme or your choice. English and Ukrainian, ready for more languages.",
        reqTitle: "Requirements",
        req1: "Android 8.0 or newer.",
        req2: "A Starlink dish that reports its location on the local network. Tested with a Gen 3 Mini on a Priority plan; other dishes may work but are untested.",
        req3: "The phone connected to the dish's Wi-Fi (the dish answers at 192.168.100.1).",
        req4: "Developer options enabled and Star Nav selected as the mock location app; the in-app Mock GPS page walks you through it.",
        privacyTitle: "Privacy",
        privacy1:
            "No accounts, analytics, ads or crash reporting. The app talks to the dish and router on your local network; it downloads map tiles only after you pick a map background, and the tile server then sees the area on screen. Recordings stay in the app's private storage until you export a part of one yourself.",
        privacy2:
            "The only exception, off until you tap it: if the dish refuses to switch on “location on the local network” from the phone, Star Nav can sign you in to your own Starlink account, on Starlink's own page and once, so Starlink switches it on; nothing is kept afterwards. Starlink publishes no API for it (it may stop working, and its terms may not allow it) and it is at your own risk.",
        privacyLink: "The full privacy policy",
        privacyPage: "This page tracks nothing either: no analytics, third-party scripts or fonts. Only Cloudflare's bot protection may set its own technical cookie.",
        safetyTitle: "Safety",
        safety: "Use it as an aid, not as your only means of navigation. The dish's position can be off by tens of metres in hard braking or under jamming, and it disappears during speed-policy cut-offs; the inertial correction's dead reckoning grows less accurate with every second. Do not operate the app while driving.",
        creditsTitle: "Credits",
        creditStarDebug:
            "by ertong, an open-source Starlink tool made for the volunteer initiative Narodnyi Starlink, inspired this app: much of what Star Nav shows and does with the dish and the router follows its lead. Thank you for building it in the open.",
        creditGrpc1: "Field numbers of the dish's local gRPC API come from reflection dumps published by",
        creditGrpc2: " and the documentation of",
        creditMore: "The other credits and licences are in the",
        creditsHref: `${REPO}#credits`,
        affiliation:
            "Star Nav is an independent open-source project. It is not affiliated with, endorsed by or supported by SpaceX. Starlink is a trademark of Space Exploration Technologies Corp.",
        license: "GPL-3.0 or later",
        footerSource: "Source on GitHub",
        previous: "Previous screenshot",
        next: "Next screenshot",
        close: "Close",
        notFound: "Page not found",
        notFoundBody: "There is nothing at this address.",
        backHome: "Back to the start",
    },
};

const readPreference = (key) => {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
};
const savePreference = (key, value) => {
    try {
        localStorage.setItem(key, value);
    } catch {
        /* Preferences are optional. */
    }
};

// ?lang=en / ?theme=dark pick a language or theme for this visit without saving it (links, screenshots).
const query = new URLSearchParams(location.search);
const darkQuery = matchMedia("(prefers-color-scheme: dark)");
let language = "uk";
let themeChoice = "system";

const shotTheme = () => (themeChoice === "system" ? (darkQuery.matches ? "dark" : "light") : themeChoice);

function updateShots() {
    const folder = `/shots/${language}-${shotTheme()}`;
    document.querySelectorAll("[data-shot]").forEach((element) => {
        const name = element.dataset.shot;
        element.srcset = `${folder}/${name}-360.webp 360w, ${folder}/${name}-720.webp 720w`;
        if (element.tagName === "IMG") {
            element.src = `${folder}/${name}-720.webp`;
        }
    });
    if (viewer?.open) {
        showShot(current);
    }
}

function setLanguage(next) {
    language = next;
    const messages = translations[language];
    document.documentElement.lang = language;
    document.title = messages.title;
    document.querySelector('meta[name="description"]')?.setAttribute("content", messages.description);
    document.querySelectorAll("[data-i18n]").forEach((element) => {
        const message = messages[element.dataset.i18n];
        if (message !== undefined) {
            element.textContent = message;
        }
    });
    document.querySelectorAll("[data-i18n-href]").forEach((element) => {
        element.href = messages[element.dataset.i18nHref];
    });
    document.querySelectorAll("[data-i18n-alt]").forEach((element) => {
        element.alt = messages[element.dataset.i18nAlt];
    });
    document.querySelectorAll("[data-i18n-label]").forEach((element) => {
        element.setAttribute("aria-label", messages[element.dataset.i18nLabel]);
        if (element.tagName === "BUTTON") {
            element.title = messages[element.dataset.i18nLabel];
        }
    });
    document.querySelectorAll("[data-theme-choice]").forEach((button) => {
        button.setAttribute("aria-label", messages[button.dataset.themeChoice]);
        button.title = messages[button.dataset.themeChoice];
    });
    document.querySelectorAll("[data-lang]").forEach((button) => {
        button.setAttribute("aria-pressed", String(button.dataset.lang === language));
    });
    updateShots();
}

function setTheme(choice) {
    themeChoice = choice;
    // The palette follows the system through CSS (light-dark()); data-theme only pins it.
    if (choice === "system") {
        delete document.documentElement.dataset.theme;
    } else {
        document.documentElement.dataset.theme = choice;
    }
    document.querySelectorAll("[data-theme-choice]").forEach((button) => {
        button.setAttribute("aria-pressed", String(button.dataset.themeChoice === choice));
    });
    updateShots();
}

// The screenshot viewer: a modal dialog with the 720 px picture, previous / next, Escape or a click outside closes.
// The 404 page has none.
const viewer = document.querySelector(".viewer");
const thumbnails = [...document.querySelectorAll(".shot")];
let current = 0;

function showShot(index) {
    current = (index + thumbnails.length) % thumbnails.length;
    const thumbnail = thumbnails[current];
    const name = thumbnail.querySelector("img").dataset.shot;
    const image = viewer.querySelector("img");
    const caption = viewer.querySelector("figcaption");
    image.src = `/shots/${language}-${shotTheme()}/${name}-720.webp`;
    const [title, text] = thumbnail.querySelectorAll(".caption > *");
    caption.textContent = `${title.textContent} — ${text.textContent}`;
    image.alt = caption.textContent;
}

if (viewer) {
    thumbnails.forEach((thumbnail, index) =>
        thumbnail.addEventListener("click", () => {
            showShot(index);
            viewer.showModal();
        }),
    );
    viewer.querySelector(".viewer-prev").addEventListener("click", () => showShot(current - 1));
    viewer.querySelector(".viewer-next").addEventListener("click", () => showShot(current + 1));
    viewer.querySelector(".viewer-close").addEventListener("click", () => viewer.close());
    viewer.addEventListener("click", (event) => {
        if (event.target === viewer) {
            viewer.close();
        }
    });
    viewer.addEventListener("keydown", (event) => {
        if (event.key === "ArrowLeft") {
            showShot(current - 1);
        } else if (event.key === "ArrowRight") {
            showShot(current + 1);
        }
    });
    viewer.addEventListener("close", () => thumbnails[current].focus());
}

const askedLanguage = query.get("lang");
const savedLanguage = readPreference("apachi-language");
setLanguage(
    askedLanguage in translations ? askedLanguage : savedLanguage in translations ? savedLanguage : "uk",
);
const themes = ["system", "light", "dark"];
const askedTheme = query.get("theme");
const savedTheme = readPreference("apachi-theme");
setTheme(themes.includes(askedTheme) ? askedTheme : themes.includes(savedTheme) ? savedTheme : "system");
darkQuery.addEventListener("change", () => themeChoice === "system" && updateShots());

document.querySelectorAll("[data-lang]").forEach((button) =>
    button.addEventListener("click", () => {
        setLanguage(button.dataset.lang);
        savePreference("apachi-language", button.dataset.lang);
    }),
);
document.querySelectorAll("[data-theme-choice]").forEach((button) =>
    button.addEventListener("click", () => {
        setTheme(button.dataset.themeChoice);
        savePreference("apachi-theme", button.dataset.themeChoice);
    }),
);
