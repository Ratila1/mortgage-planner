import { BrowserRouter, Navigate, Route, Routes } from 'react-router';
import { AuthProvider } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { NotForAdmin, RequireAuth } from './components/RequireAuth';
import { AdminPanelPage } from './pages/AdminPanelPage';
import { LoginPage, RegisterPage, VerifyEmailPage } from './pages/AuthPages';
import { CalculatorPage } from './pages/CalculatorPage';
import { MicroloanCalculatorPage } from './pages/MicroloanCalculatorPage';
import { MicroloanApplicationPage } from './pages/MicroloanApplicationPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { UserCabinetPage } from './pages/UserCabinetPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<NotForAdmin><CalculatorPage /></NotForAdmin>} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="verify-email" element={<VerifyEmailPage />} />
        {/* Личный кабинет: вкладка определяется URL (см. UserCabinetPage). */}
        <Route path="cabinet" element={<Navigate to="/scenarios" replace />} />
        <Route path="scenarios" element={<RequireAuth userOnly><UserCabinetPage /></RequireAuth>} />
        <Route path="compare" element={<RequireAuth userOnly><UserCabinetPage /></RequireAuth>} />
        <Route path="cabinet/applications" element={<RequireAuth userOnly><UserCabinetPage /></RequireAuth>} />
        <Route path="notifications" element={<RequireAuth><NotificationsPage /></RequireAuth>} />
        <Route path="admin" element={<RequireAuth admin><AdminPanelPage /></RequireAuth>} />
        <Route path="microloan" element={<NotForAdmin><MicroloanCalculatorPage /></NotForAdmin>} />
        <Route path="microloan/application" element={<RequireAuth userOnly><MicroloanApplicationPage /></RequireAuth>} />
        <Route path="microloan/applications" element={<Navigate to="/cabinet/applications" replace />} />
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
