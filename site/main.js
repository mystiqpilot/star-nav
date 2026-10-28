// The page's words in both languages (index.html carries the Ukrainian ones statically; tests/text-check.py
// keeps them equal), the language and theme switches, and the screenshot viewer.
const REPO = "https://github.com/mystiqpilot/star-nav";
const translations = {
    uk: {
        title: "Star Nav — GPS, якому смартфон може довіряти",
        description:
            "Star Nav — застосунок для Android з відкритим кодом: положення антени Starlink як GPS смартфона, коли GNSS глушать чи підміняють.",
        theme: "Тема",
        system: "Як у системі",
        light: "Світла тема",
        dark: "Темна тема",
        eyebrow: "Застосунок для Android · відкритий код, GPL-3.0",
        tagline: "GPS, якому смартфон може довіряти, коли супутникову навігацію глушать або підміняють.",
        intro:
            "Коли GNSS глушать або підміняють, GPS у смартфоні зависає, «пливе» або перестрибує на інший континент, а за ним і всі навігаційні застосунки. Антена Starlink на даху авто при цьому знає, де вона є. Star Nav віддає її положення всім застосункам на смартфоні як GPS Android. Екстрені служби можуть використовувати його у своїх автомобілях, щоб не втрачати навігацію під час підміни GPS.",
        download: "Завантажити",
        releasesHref: `${REPO}/releases/latest`,
        source: "Код на GitHub",
        howLink: "Як це працює",
        releaseNote: "Безкоштовно. Потрібні Android 8.0 або новіший і тариф Starlink Priority. Можна й зібрати з коду:",
        buildLink: "як це зробити",
        buildHref: `${REPO}/blob/main/README.uk.md#збирання-з-коду`,
        heroAlt: "Сторінка «Позиція»: позиція антени Starlink і GPS смартфона",
        screensTitle: "Екрани",
        screensLead: "Унизу п'ять сторінок. Знімки зроблено на емуляторі з тестовою антеною.",
        shotPosition: "Позиція",
        shotPositionText: "Позиція антени й GPS смартфона, кожна зі своїм станом",
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
        step1Text: "Антена Starlink на даху авто знає, де вона є, хоч би що показував GPS смартфона.",
        step2: "Її локальна мережа",
        step2Text: "Смартфон під'єднаний до Wi-Fi антени; Star Nav читає її положення з локального API.",
        step3: "Корекція на смартфоні",
        step3Text:
            "Датчики руху смартфона й курс антени тримають траєкторію, коли позиція антени «пливе» вбік або зникає.",
        step4: "Кожен застосунок",
        step4Text:
            "Усі застосунки на смартфоні отримують позицію як GPS Android — через механізм фіктивного місцезнаходження.",
        featuresTitle: "Що вміє",
        fGps: "Положення антени як GPS смартфона",
        fGpsText:
            "Найкраще положення антени йде в Android як провайдер GPS, зсунуте вперед на свій вік, щоб не відставати на трасі.",
        fInertial: "Інерціальна корекція",
        fInertialText:
            "У режимі Авто датчики руху смартфона тримають авто на траєкторії, коли позиція антени зсувається вбік, а під час збоїв антени ведуть позицію далі численням шляху й кажуть застосункам, наскільки вона ще точна.",
        fModes: "Один перемикач, три режими",
        fModesText:
            "Авто (рекомендовано), Сирий (позиція антени як є) і Лише Starlink (джерело антени без GNSS). Також з плитки швидких налаштувань і зі сповіщення.",
        fCutoff: "Відсічки й перезапуски",
        fCutoffText:
            "Показує, коли антена вимикає місцезнаходження («рухається занадто швидко для тарифу») або перезапускається, і чому.",
        fChecklist: "Перелік налаштувань",
        fChecklistText: "Усе, що потрібно смартфону, зі станом і кнопкою до потрібної сторінки налаштувань.",
        fRecorder: "Запис і звіти про точність",
        fRecorderText:
            "Записує антену, GNSS і датчики руху смартфона; кожна сесія має звіт із треком, розфарбованим за похибкою. Запис можна повністю вимкнути.",
        fExport: "Приватний експорт",
        fExportText: "Ви обираєте, яка частина запису покине смартфон; ідентифікатори антени й роутера видаляються.",
        fStarlink: "Антена й роутер",
        fStarlinkText:
            "Усе, що пропонує локальний API антени, простими словами: стан, сповіщення, графіки, карта перешкод, керування, а також пристрої й Wi-Fi роутера. Кожна зміна спершу питає.",
        fLook: "Світла й темна тема, дві мови",
        fLookText: "Як в Android або на ваш вибір. Українська й англійська.",
        reqTitle: "Вимоги",
        req1: "Android 8.0 або новіший.",
        reqPlan:
            "Тариф Starlink Priority (Local Priority або Global Priority): з травня 2026 року Starlink віддає місцезнаходження антени в локальну мережу тільки на цих тарифах.",
        reqPlanLink: "Що таке тарифи Priority (Starlink, англійською)",
        req2:
            "Антена, що повідомляє своє місцезнаходження в локальній мережі. Перевірено на Gen 3 Mini; інші антени можуть працювати, але не перевірені.",
        req3: "Смартфон під'єднаний до Wi-Fi антени (антена відповідає за адресою 192.168.100.1).",
        req4:
            "Увімкнені параметри розробника, і Star Nav обрано програмою для фіктивного місцезнаходження; сторінка «Імітація» проведе вас крок за кроком.",
        privacyTitle: "Приватність",
        privacy1:
            "Жодних облікових записів, аналітики, реклами чи звітів про збої. Застосунок спілкується з антеною та роутером у вашій локальній мережі, а із сервером тайлів — лише після того, як ви оберете підкладку мапи. Записи лишаються на смартфоні, доки ви самі не експортуєте частину.",
        privacy2:
            "Єдиний виняток, лише за вашим натисканням: якщо антена відмовляється вмикати місцезнаходження в локальній мережі, Star Nav може один раз провести вас у ваш власний обліковий запис Starlink на сторінці Starlink, щоб Starlink це ввімкнув; нічого не зберігається. Starlink не публікує для цього API: воно може перестати працювати або суперечити умовам Starlink, тож це на ваш власний ризик.",
        privacyLink: "Повна політика приватності (англійською)",
        privacyPage: "Ця сторінка теж нічого не відстежує: без аналітики, сторонніх скриптів і шрифтів. Лише захист Cloudflare від ботів може ставити свій технічний файл cookie.",
        safetyTitle: "Безпека",
        safety:
            "Використовуйте як допомогу, а не як єдиний засіб навігації. Положення антени може помилятися на десятки метрів при різкому гальмуванні чи під завадами і зникає під час відсічок; числення шляху з кожною секундою менш точне. Не керуйте застосунком під час водіння.",
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
        intro:
            "When GNSS is jammed or spoofed, the phone's GPS freezes, drifts or jumps to another continent, and every navigation app follows it. A Starlink dish on the car roof still knows where it is. Star Nav gives its position to every app on the phone as Android's GPS. Emergency services can use it in their vehicles to keep navigating while GPS is spoofed.",
        download: "Download",
        releasesHref: `${REPO}/releases/latest`,
        source: "Source on GitHub",
        howLink: "How it works",
        releaseNote:
            "Free. Needs Android 8.0 or newer and a Starlink Priority plan. You can also build it from source:",
        buildLink: "how to",
        buildHref: `${REPO}#build-from-source`,
        heroAlt: "The Position page: the Starlink dish's position and the phone's GPS",
        screensTitle: "Screens",
        screensLead: "Five pages in the bottom bar. Screenshots taken on the emulator with a test dish.",
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
        step1Text: "The Starlink dish on the car roof knows where it is, whatever the phone's GPS shows.",
        step2: "Its local network",
        step2Text: "The phone is on the dish's Wi-Fi; Star Nav reads the dish's position from its local API.",
        step3: "Correction on the phone",
        step3Text:
            "The phone's motion sensors and the dish heading hold the track when the dish position drifts sideways or drops out.",
        step4: "Every app",
        step4Text: "Every app on the phone gets the position as Android's GPS, through the mock location provider.",
        featuresTitle: "What it does",
        fGps: "Dish position as the phone's GPS",
        fGpsText:
            "The dish's best position goes to Android as the GPS provider, moved forward by its age so it keeps up at highway speed.",
        fInertial: "Inertial correction",
        fInertialText:
            "In Auto mode the phone's motion sensors hold the car on its track when the dish position drifts sideways, and carry it on by dead reckoning through dish outages, telling apps how accurate it still is.",
        fModes: "One switch, three modes",
        fModesText:
            "Auto (recommended), Raw (the dish's position as reported) and Starlink only (the dish's GNSS-free source). Also from a Quick Settings tile and the notification.",
        fCutoff: "Cut-offs and restarts",
        fCutoffText:
            "Shows when the dish switches its location off (“moving too fast for the plan”) or restarts, and why.",
        fChecklist: "Setup checklist",
        fChecklistText: "Every setting the phone needs, with its state and a button to the exact settings page.",
        fRecorder: "Recorder and accuracy reports",
        fRecorderText:
            "Records the dish, the phone's GNSS and motion sensors; each session gets a report with the track coloured by error. Logging can be switched off completely.",
        fExport: "Private export",
        fExportText: "You choose which part of a recording leaves the phone; dish and router identifiers are removed.",
        fStarlink: "Dish and router",
        fStarlinkText:
            "Everything the dish's local API offers, in plain words: state, alerts, charts, obstruction map, controls, and the router's devices and Wi-Fi. Every change asks first.",
        fLook: "Light and dark theme, two languages",
        fLookText: "Follows Android's theme or your choice. English and Ukrainian.",
        reqTitle: "Requirements",
        req1: "Android 8.0 or newer.",
        reqPlan:
            "A Starlink Priority plan (Local Priority or Global Priority): since May 2026 Starlink gives the dish's location to the local network only on these plans.",
        reqPlanLink: "What Priority plans are (Starlink)",
        req2:
            "A dish that reports its location on the local network. Tested with a Gen 3 Mini; other dishes may work but are untested.",
        req3: "The phone connected to the dish's Wi-Fi (the dish answers at 192.168.100.1).",
        req4:
            "Developer options on and Star Nav chosen as the mock location app; the Mock GPS page walks you through it.",
        privacyTitle: "Privacy",
        privacy1:
            "No accounts, analytics, ads or crash reporting. The app talks to the dish and router on your local network, and to a map tile server only after you pick a map background. Recordings stay on the phone until you export a part yourself.",
        privacy2:
            "One opt-in exception: if the dish refuses to switch on its local-network location, Star Nav can sign you in once to your own Starlink account, on Starlink's page, so Starlink switches it on; nothing is kept. Starlink publishes no API for this: it may stop working or go against Starlink's terms, so it is at your own risk.",
        privacyLink: "The full privacy policy",
        privacyPage: "This page tracks nothing either: no analytics, third-party scripts or fonts. Only Cloudflare's bot protection may set its own technical cookie.",
        safetyTitle: "Safety",
        safety:
            "Use it as an aid, not as your only means of navigation. The dish's position can be off by tens of metres in hard braking or under jamming and disappears during cut-offs; dead reckoning loses accuracy every second. Do not operate the app while driving.",
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
