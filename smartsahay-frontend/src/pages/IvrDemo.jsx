import IvrKeypad from '../components/IvrKeypad';

// Same base URL the rest of the app uses (see src/services/api.js and .env)
const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/smartsahay';

export default function IvrDemo() {
  return (
    <div style={{ padding: '2rem 1rem' }}>
      <h1 style={{ textAlign: 'center', marginBottom: '0.25rem' }}>Voice Helpline (Demo)</h1>
      <p style={{ textAlign: 'center', marginBottom: '1.5rem' }}>
        Press Call, then use the keypad or your keyboard. Prompts are read aloud.
      </p>
      <IvrKeypad apiBase={API_BASE} askExtra={false} />
    </div>
  );
}
