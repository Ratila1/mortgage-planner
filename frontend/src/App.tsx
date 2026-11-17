import { BrowserRouter, Route, Routes } from 'react-router';
import { AuthProvider } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { RequireAuth } from './components/RequireAuth';
import { AdminOffersPage } from './pages/AdminOffersPage';
import { AdminMicroloanTypesPage } from './pages/AdminMicroloanTypesPage';
import { AdminMicroloanApplicationsPage } from './pages/AdminMicroloanApplicationsPage';
import { LoginPage, RegisterPage, VerifyEmailPage } from './pages/AuthPages';
import { CalculatorPage } from './pages/CalculatorPage';
import { ComparePage } from './pages/ComparePage';
import { MicroloanCalculatorPage } from './pages/MicroloanCalculatorPage';
import { MicroloanApplicationPage } from './pages/MicroloanApplicationPage';
import { MicroloanApplicationsPage } from './pages/MicroloanApplicationsPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { ScenariosPage } from './pages/ScenariosPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<CalculatorPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="verify-email" element={<VerifyEmailPage />} />
        <Route path="scenarios" element={<RequireAuth><ScenariosPage /></RequireAuth>} />
        <Route path="compare" element={<RequireAuth><ComparePage /></RequireAuth>} />
        <Route path="notifications" element={<RequireAuth><NotificationsPage /></RequireAuth>} />
        <Route path="admin/offers" element={<RequireAuth admin><AdminOffersPage /></RequireAuth>} />
        <Route path="admin/microloan" element={<RequireAuth admin><AdminMicroloanTypesPage /></RequireAuth>} />
        <Route path="admin/microloan/applications" element={<RequireAuth admin><AdminMicroloanApplicationsPage /></RequireAuth>} />
        <Route path="microloan" element={<MicroloanCalculatorPage />} />
        <Route path="microloan/application" element={<RequireAuth><MicroloanApplicationPage /></RequireAuth>} />
        <Route path="microloan/applications" element={<RequireAuth><MicroloanApplicationsPage /></RequireAuth>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}
