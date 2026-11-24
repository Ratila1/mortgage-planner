package dev.nosurprise.bureau.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    @Test
    @DisplayName("режим поиска: оба параметра, только телефон, только ФИО")
    void searchBy() {
        assertThat(SearchQuery.of("+375291234567", "Иванов", "Иван", null).searchBy())
                .isEqualTo(SearchQuery.SearchBy.PHONE_AND_NAME);
        assertThat(SearchQuery.of("+375291234567", null, null, null).searchBy()).isEqualTo(SearchQuery.SearchBy.PHONE);
        assertThat(SearchQuery.of(null, "Иванов", "Иван", null).searchBy()).isEqualTo(SearchQuery.SearchBy.NAME);
    }

    @Test
    @DisplayName("нормализация как во VIEW монолита: регистр, «ё», пробелы; телефон без скобок и дефисов")
    void normalization() {
        SearchQuery query = SearchQuery.of(" +375 (29) 123-45-67 ", "  ФЁДОРОВ ", "Пётр", "Семёнович");

        assertThat(query.phone()).isEqualTo("+375291234567");
        assertThat(query.lastName()).isEqualTo("федоров");
        assertThat(query.firstName()).isEqualTo("петр");
        assertThat(query.middleName()).isEqualTo("семенович");
    }

    @Test
    @DisplayName("неверные комбинации и формат → ошибка")
    void invalid() {
        assertThatThrownBy(() -> SearchQuery.of(null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchQuery.of(null, "Иванов", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchQuery.of("+375291234567", null, null, "Иванович"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchQuery.of("8029123", null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("совпадение: оба параметра — оба; отчество — только если прислали")
    void matches() {
        SearchQuery both = SearchQuery.of("+375291234567", "Иванов", "Иван", null);
        assertThat(both.matches("+375291234567", "иванов", "иван", "иванович")).isTrue();
        assertThat(both.matches("+375290000000", "иванов", "иван", "иванович")).isFalse();
        assertThat(both.matches("+375291234567", "петров", "иван", "")).isFalse();

        SearchQuery withMiddle = SearchQuery.of(null, "Иванов", "Иван", "Петрович");
        assertThat(withMiddle.matches("+1", "иванов", "иван", "иванович")).isFalse();
        assertThat(withMiddle.matches("+1", "иванов", "иван", "петрович")).isTrue();
    }

    @Test
    @DisplayName("хеш стабилен после нормализации и не содержит самих данных")
    void hash() {
        String a = SearchQuery.of("+375 29 123-45-67", "ИВАНОВ", "иван", null).hash();
        String b = SearchQuery.of("+375291234567", "Иванов", "Иван", null).hash();

        assertThat(a).isEqualTo(b).hasSize(64).doesNotContain("375291234567");
    }
}
