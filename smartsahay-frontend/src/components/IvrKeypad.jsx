import { useEffect, useState } from 'react';
import './IvrKeypad.css';

/**
 * Simulated IVR: an on-screen phone keypad that speaks prompts with the browser's
 * speechSynthesis and calls the SmartSahay backend.
 *
 * Props
 *   apiBase    backend base URL, e.g. "http://localhost:8080/smartsahay" ('' = same origin / dev proxy)
 *   askExtra   true  -> also ask FRUITS ID and Aadhaar-linked bank (8 questions)
 *              false -> only the 6 questions the backend's rules use
 *
 * Scheme-information mode is a two-level menu that the backend builds from the database:
 *   main menu (1 Central, 2 Karnataka state) -> schemes of that group, 8 per page (9 = more, 0 = back) -> summary.
 * The page keeps no list of schemes itself, so it cannot disagree with the backend.
 *
 * This is a demo mode. It is not a telephone service and needs no phone network.
 */

const range = (min, max) => (d) => d !== '' && Number(d) >= min && Number(d) <= max;

function buildQuestions(askExtra) {
  const qs = [
    { key: 'state', multi: false, valid: range(1, 2),
      prompt: 'Press 1 if your farm is in Karnataka, or press 2 for another state.' },
    { key: 'age', multi: true, valid: range(18, 100),
      prompt: 'Enter your age in years, then press hash.' },
    { key: 'category', multi: false, valid: range(1, 5),
      prompt: 'Press 1 for General, 2 for S C, 3 for S T, 4 for O B C, or 5 for Minority.' },
    { key: 'acres', multi: true, valid: range(0, 500),
      prompt: 'Enter your land in whole acres, then press hash. Press 0 if you have no land.' },
    { key: 'income', multi: false, valid: range(1, 5),
      prompt: 'Yearly family income. Press 1 for below 1 lakh, 2 for 1 to 3 lakh, 3 for 3 to 6 lakh, ' +
              '4 for 6 to 8 lakh, or 5 for above 8 lakh.' },
    { key: 'crop', multi: false, valid: range(1, 4),
      prompt: 'Main crop. Press 1 for Kharif, 2 for Rabi, 3 for millets, or 4 for other.' },
  ];
  if (askExtra) {
    qs.push(
      { key: 'fruits', multi: false, valid: range(1, 2),
        prompt: 'Do you have a FRUITS ID? Press 1 for yes, or 2 for no.' },
      { key: 'bank', multi: false, valid: range(1, 2),
        prompt: 'Is your bank account linked with Aadhaar? Press 1 for yes, or 2 for no.' },
    );
  }
  return qs;
}

function speak(text) {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
  window.speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  const voices = window.speechSynthesis.getVoices();
  const voice = voices.find((v) => v.lang === 'en-IN') || voices.find((v) => v.lang.startsWith('en'));
  if (voice) u.voice = voice;
  u.lang = voice ? voice.lang : 'en-IN';
  window.speechSynthesis.speak(u);
}

export default function IvrKeypad({ apiBase = '', askExtra = false }) {
  const questions = buildQuestions(askExtra);

  const [mode, setMode] = useState('scheme');       // 'scheme' | 'eligibility'
  const [inCall, setInCall] = useState(false);
  const [busy, setBusy] = useState(false);
  const [step, setStep] = useState(0);
  const [buffer, setBuffer] = useState('');
  const [answers, setAnswers] = useState({});
  const [log, setLog] = useState([]);
  // Scheme-information menu state (the lists themselves always come from the server)
  const [group, setGroup] = useState(null);         // null = main menu, 1 = Central, 2 = Karnataka state
  const [page, setPage] = useState(0);
  const [options, setOptions] = useState([]);       // [{key, label}] for the menu on screen
  const [menuText, setMenuText] = useState('');     // spoken text of the current menu, for repeating

  function say(text) {
    setLog((l) => [...l, text]);
    speak(text);
  }

  // Returns { text, options } (options is null for plain answers). Never throws.
  async function callApi(path, init) {
    setBusy(true);
    try {
      const res = await fetch(`${apiBase}${path}`, init);
      const data = await res.json().catch(() => null);
      if (!res.ok) return { text: 'Sorry, the service is not available right now. Please try again later.', options: null };
      return { text: data?.gather_prompt?.text || 'Sorry, something went wrong.', options: data?.options || null, level: data?.level || null };
    } catch {
      return { text: 'Sorry, I could not reach the Smart Sahay server.', options: null };
    } finally {
      setBusy(false);
    }
  }

  // Load a menu from the server and make it the current one.
  async function loadMenu(nextGroup, nextPage) {
    const query = nextGroup == null ? '' : `?group=${nextGroup}&page=${nextPage}`;
    const r = await callApi(`/api/ivr/menu${query}`);
    if (r.options) {
      // The server says which level it sent, so a fallback to the main menu is followed automatically.
      const isMain = r.level !== 'list';
      setGroup(isMain ? null : nextGroup);
      setPage(isMain ? 0 : nextPage);
      setOptions(r.options);
      setMenuText(r.text);
    }
    say(r.text);
  }

  function startCall() {
    setInCall(true);
    setLog([]);
    setBuffer('');
    setAnswers({});
    setStep(0);
    setGroup(null);
    setPage(0);
    setOptions([]);
    if (mode === 'scheme') {
      loadMenu(null, 0);
    } else {
      say('Welcome to Smart Sahay. I will ask a few questions to find schemes for you. ' + questions[0].prompt);
    }
  }

  function endCall() {
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    setInCall(false);
    setBuffer('');
  }

  async function pressScheme(k) {
    const offered = options.some((o) => o.key === k);

    if (group == null) {                                   // main menu
      if (k === '0') { say(menuText); return; }            // repeat
      if (offered) { await loadMenu(Number(k), 0); return; }
    } else {                                               // list of schemes
      if (k === '0') { await loadMenu(null, 0); return; }  // back to main menu
      if (k === '9' && offered) { await loadMenu(group, page + 1); return; }
      if (offered) {
        const r = await callApi(`/api/ivr/scheme?group=${group}&page=${page}&digits=${k}`);
        say(r.text);
        return;
      }
    }
    say('That key is not on the menu. ' + menuText);
  }

  async function submit(value) {
    const q = questions[step];
    if (!q.valid(value)) {
      setBuffer('');
      say('Sorry, that is not valid. ' + q.prompt);
      return;
    }
    const next = { ...answers, [q.key]: Number(value) };
    setBuffer('');
    if (step + 1 < questions.length) {
      setAnswers(next);
      setStep(step + 1);
      say(questions[step + 1].prompt);
      return;
    }
    // Last answer: send everything in a POST body (never in the URL).
    const r = await callApi('/api/ivr/eligibility', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(next),
    });
    say(r.text + ' Thank you for calling Smart Sahay. Goodbye.');
    setInCall(false);
  }

  function press(k) {
    if (!inCall || busy) return;
    if (mode === 'scheme') {
      pressScheme(k);
      return;
    }
    const q = questions[step];
    if (q.multi) {
      if (/^[0-9]$/.test(k)) setBuffer((b) => (b + k).slice(0, 3));
      else if (k === '*') setBuffer('');
      else if (k === '#') submit(buffer);
    } else if (/^[0-9]$/.test(k)) {
      submit(k);
    }
  }

  // Physical keyboard works while a call is active.
  useEffect(() => {
    if (!inCall) return undefined;
    const onKey = (e) => {
      const tag = e.target && e.target.tagName;
      if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
      if (/^[0-9*#]$/.test(e.key)) press(e.key);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  // Stop speaking when leaving the page.
  useEffect(() => () => {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) window.speechSynthesis.cancel();
  }, []);

  const lastPrompt = log.length ? log[log.length - 1] : 'Choose a mode and press Call.';
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#'];

  return (
    <div className="ivr">
      <div className="ivr-modes" role="radiogroup" aria-label="IVR mode">
        <button type="button" role="radio" aria-checked={mode === 'scheme'} disabled={inCall}
                className={mode === 'scheme' ? 'ivr-mode active' : 'ivr-mode'}
                onClick={() => setMode('scheme')}>Scheme information</button>
        <button type="button" role="radio" aria-checked={mode === 'eligibility'} disabled={inCall}
                className={mode === 'eligibility' ? 'ivr-mode active' : 'ivr-mode'}
                onClick={() => setMode('eligibility')}>Check eligibility</button>
      </div>

      <div className="ivr-phone">
        <div className="ivr-screen" aria-live="polite">
          <p className="ivr-prompt">{lastPrompt}</p>
          {inCall && mode === 'eligibility' && questions[step]?.multi && (
            <p className="ivr-buffer">Typing: {buffer || '…'} (press # to confirm, * to clear)</p>
          )}
          {inCall && mode === 'eligibility' && (
            <p className="ivr-step">Question {step + 1} of {questions.length}</p>
          )}
          {busy && <p className="ivr-step">Please wait…</p>}
        </div>

        {mode === 'scheme' && inCall && options.length > 0 && (
          <ul className="ivr-menu">
            {options.map((o) => <li key={o.key}><strong>{o.key}</strong> {o.label}</li>)}
            <li><strong>0</strong> {group == null ? 'Repeat this menu' : 'Main menu'}</li>
          </ul>
        )}

        <div className="ivr-keys">
          {keys.map((k) => (
            <button type="button" key={k} className="ivr-key" disabled={!inCall || busy}
                    onClick={() => press(k)} aria-label={`Key ${k}`}>{k}</button>
          ))}
        </div>

        <div className="ivr-actions">
          {!inCall
            ? <button type="button" className="ivr-call" onClick={startCall}>📞 Call</button>
            : <button type="button" className="ivr-end" onClick={endCall}>End call</button>}
        </div>
      </div>

      {log.length > 1 && (
        <details className="ivr-log">
          <summary>Call transcript</summary>
          <ol>{log.map((t, i) => <li key={i}>{t}</li>)}</ol>
        </details>
      )}

      <p className="ivr-note">
        Demo mode: this simulates a phone helpline in your browser. It is not a real phone number.
        Results are indicative only; please confirm with your local agriculture office.
      </p>
    </div>
  );
}
