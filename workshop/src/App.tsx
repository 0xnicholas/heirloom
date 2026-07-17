import { Routes, Route, Navigate } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { PlaceholderPage } from './components/shared/PlaceholderPage'
import { StatsPage } from './pages/StatsPage'
import { SchemaPage } from './pages/SchemaPage'
import { ExplorerPage } from './pages/ExplorerPage'
import { BuilderPage } from './pages/BuilderPage'
import { AttributesPage } from './pages/AttributesPage'
import { RelationshipsPage } from './pages/RelationshipsPage'
import { GraphPage } from './pages/GraphPage'
import { ActionsPage } from './pages/ActionsPage'
import { RolesPage } from './pages/RolesPage'
import { FunctionsPage } from './pages/FunctionsPage'
import { EventsPage } from './pages/EventsPage'
import { QueryPage } from './pages/QueryPage'
import { ConsolePage } from './pages/ConsolePage'
import { SettingsPage } from './pages/SettingsPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<StatsPage />} />
        <Route path="/schema" element={<SchemaPage />} />
        <Route path="/schema/:typeName" element={<SchemaPage />} />
        <Route path="/explorer" element={<ExplorerPage />} />
        <Route path="/objects" element={<Navigate to="/explorer" replace />} />
        <Route path="/builder" element={<BuilderPage />} />
        <Route path="/attributes" element={<AttributesPage />} />
        <Route path="/relationships" element={<RelationshipsPage />} />
        <Route path="/graph" element={<GraphPage />} />
        <Route path="/actions" element={<ActionsPage />} />
        <Route path="/actions/:actionName" element={<ActionsPage />} />
        <Route path="/roles" element={<RolesPage />} />
        <Route path="/roles/:roleName" element={<RolesPage />} />
        <Route path="/functions" element={<FunctionsPage />} />
        <Route path="/queries" element={<QueryPage />} />
        <Route path="/console" element={<ConsolePage />} />
        <Route path="/events" element={<EventsPage />} />
        <Route path="/settings" element={<SettingsPage />} />

        {/* Placeholder concept pages */}
        <Route path="/rules" element={
          <PlaceholderPage title="Rules" description="Declarative constraints and validations across the ontology." />
        } />
        <Route path="/violations" element={
          <PlaceholderPage title="Violations" description="Detected rule violations and remediation queue." />
        } />
        <Route path="/tasks" element={
          <PlaceholderPage title="Tasks" description="Human-in-the-loop tasks and approvals." />
        } />
        <Route path="/forms" element={
          <PlaceholderPage title="Forms" description="Generated input forms for actions and resource creation." />
        } />
        <Route path="/inbox" element={
          <PlaceholderPage title="Inbox" description="Notifications and messages for agents and users." />
        } />
        <Route path="/chat" element={
          <PlaceholderPage title="Chat" description="Conversational interface for agent interaction." />
        } />
      </Route>
    </Routes>
  )
}
