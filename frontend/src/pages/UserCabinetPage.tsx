import { useLocation, useNavigate } from 'react-router';
import { ScenariosPage } from './ScenariosPage';
import { ComparePage } from './ComparePage';
import { MicroloanApplicationsPage } from './MicroloanApplicationsPage';
import { PageTitle, Segmented, Card } from '../components/ui';

type CabinetTab = 'scenarios' | 'compare' | 'applications';

/**
 * Вкладка кабинета — часть URL: на /scenarios и /compare?ids=… ведут ссылки из калькулятора,
 * входа и уведомлений, поэтому без маршрутов на них был 404.
 */
const TAB_PATHS: Record<CabinetTab, string> = {
  scenarios: '/scenarios',
  compare: '/compare',
  applications: '/cabinet/applications',
};

const TABS: Array<{ value: CabinetTab; label: string }> = [
  { value: 'scenarios', label: 'Мои сценарии' },
  { value: 'compare', label: 'Сравнение' },
  { value: 'applications', label: 'Мои заявки' },
];

export function cabinetTabFromPath(pathname: string): CabinetTab | null {
  const entry = (Object.entries(TAB_PATHS) as Array<[CabinetTab, string]>).find(([, path]) => pathname.startsWith(path));
  return entry ? entry[0] : null;
}

export function UserCabinetPage() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const activeTab = cabinetTabFromPath(pathname) ?? 'scenarios';

  return (
    <div className="space-y-8">
      <PageTitle eyebrow="Личный кабинет" title="Мой профиль" />

      <Segmented
        value={activeTab}
        options={TABS.map((t) => ({ value: t.value, label: t.label }))}
        onChange={(tab) => navigate(TAB_PATHS[tab], { viewTransition: true })}
        label="Разделы"
      />

      <Card className="mt-4">
        {activeTab === 'scenarios' && <ScenariosPage />}
        {activeTab === 'compare' && <ComparePage />}
        {activeTab === 'applications' && <MicroloanApplicationsPage />}
      </Card>
    </div>
  );
}
