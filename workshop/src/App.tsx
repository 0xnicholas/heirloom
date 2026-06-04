import { Routes, Route, Navigate } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { SchemaPage } from './pages/SchemaPage'
import { QueryPage } from './pages/QueryPage'
import { SecurityPage } from './pages/SecurityPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<Navigate to="/schema" replace />} />
        <Route path="/schema" element={<SchemaPage />} />
        <Route path="/schema/:typeName" element={<SchemaPage />} />
        <Route path="/query" element={<QueryPage />} />
        <Route path="/security" element={<SecurityPage />} />
        <Route path="/security/roles/:roleName" element={<SecurityPage />} />
        <Route path="/security/actions/:actionName" element={<SecurityPage />} />
      </Route>
    </Routes>
  )
}
