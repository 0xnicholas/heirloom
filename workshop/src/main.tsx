import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.tsx';
import { AppProviders } from './components/providers/AppProviders.tsx';
import './index.css';

async function bootstrap() {
  if (import.meta.env.VITE_API_MODE === 'mock') {
    const { worker } = await import('./mocks/browser');
    await worker.start({ onUnhandledRequest: 'bypass' });
  }

  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <AppProviders>
        <App />
      </AppProviders>
    </React.StrictMode>,
  );
}

bootstrap();
