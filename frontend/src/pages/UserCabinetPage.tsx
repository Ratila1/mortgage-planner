import { useState } from 'react';
import { useNavigate } from 'react-router';
import { ScenariosPage } from './ScenariosPage';
import { ComparePage } from './ComparePage';
import { MicroloanApplicationsPage } from './MicroloanApplicationsPage';
import { PageTitle, Segmented, Card, cx } from '../components/ui';

type CabinetTab = 'scenarios' | 'compare' | 'applications';

const TABS: Array<{ value: CabinetTab; label: string }> = [
  { value: 'scenarios', label: 'Мои сценарии' },
  { value: 'compare', label: 'Сравнение' },
  { value: 'applications', label: 'Мои заявки' },
];

export function UserCabinetPage() {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<CabinetTab>('scenarios');

  return (
    <div className="space-y-8">
      <PageTitle eyebrow="Личный кабинет" title="Мой профиль" />
      
      <Segmented
        value={activeTab}
        options={TABS.map((t) => ({ value: t.value, label: t.label }))}
        onChange={setActiveTab}
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