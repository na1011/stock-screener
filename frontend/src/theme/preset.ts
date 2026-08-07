import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'

/**
 * 색·간격·반경의 **정본**.
 *
 * `main.css`는 여기서 나온 `--p-*`를 소비만 한다. 색을 두 곳에 적으면 테마를 바꿀 때
 * 반드시 한쪽이 남는다(그 원칙은 처음부터 `main.css` 머리말에 있었고, 이 파일은 그 "정본"을
 * Aura 기본값에서 우리 것으로 옮긴 것이다).
 *
 * 다크 한 벌만 만든다(ADR-0021). `colorScheme.light`를 손대지 않는 이유는 쓰지 않는 팔레트를
 * 유지보수하면 **검증되지 않은 채로 낡기** 때문이다 — 나중에 라이트를 켜면 Aura 기본값이
 * 나오지, 어설프게 손댄 반쪽 팔레트가 나오지 않는다.
 *
 * IMPORTANT — 색 예산이 이미 꽉 차 있다. success(충족)·danger(불충족)·warn(데이터 없음)은
 * `lib/outcome.ts`가 세 판정에 못 박은 것이고, orange는 지지·저항 참고정보 **전용**이다
 * (`PriceReferencePanel`). 그래서 아래에서 두 가지를 강제한다.
 *
 * 1. **accent(primary)는 파랑 계열로 둔다.** 초록·빨강·노랑·주황 어디와도 겹치지 않는다.
 * 2. **Tag의 `warn`을 amber로 덮는다.** Aura 기본값은 `{orange.*}`이라 지지·저항의 주황과
 *    같은 색이 되고, 그러면 "판정하지 못했다"와 "매매 신호가 아니다"가 같은 색으로 보인다.
 */

/**
 * 잉크블루 램프. 950이 캔버스와 입력 배경, 900이 패널, 850이 떠 있는 것, 700이 hairline이다.
 *
 * 850은 표준 단계가 아니다. 오버레이 배경이 `#161d2b` 리터럴로 램프 **밖에** 있었고, 그래서
 * "떠 있는 것은 패널보다 밝다"는 규칙이 램프를 읽어서는 보이지 않았다. 값은 그대로 두고
 * 이름만 램프 안으로 들여왔다 — `--p-surface-850`이 생기고 `.vue`도 그것을 쓸 수 있다.
 */
const ink = {
  0: '#ffffff',
  50: '#eef1f6',
  100: '#dde3ec',
  200: '#c2cbd9',
  300: '#a3aec1',
  400: '#8792a6',
  500: '#616d82',
  600: '#3e4a5e',
  700: '#2a3444',
  800: '#1a2130',
  850: '#161d2b',
  900: '#121722',
  950: '#0a0d13',
} as const

/** 코발트. 다크에서 실제로 쓰이는 것은 400(기본)·300(hover)·200(active)이다. */
const cobalt = {
  50: '#ebf3ff',
  100: '#d6e6ff',
  200: '#adccff',
  300: '#7faeff',
  400: '#4d93ff',
  500: '#2b7bff',
  600: '#1361eb',
  700: '#0b4cbd',
  800: '#0a3d94',
  900: '#0c3273',
  950: '#071e45',
} as const

export const ScreenerPreset = definePreset(Aura, {
  primitive: {
    // 데이터 화면이라 모서리를 낮춘다. 둥글수록 셀 경계가 흐려지고 표가 물러 보인다.
    //
    // 컨트롤 높이를 32px로 조이면서 한 단계 더 내렸다. 반경은 절대값이 아니라 높이 대비
    // 비율로 읽힌다 — 35px 높이에 5px과 32px 높이에 5px은 다르게 보인다.
    borderRadius: { none: '0', xs: '2px', sm: '3px', md: '4px', lg: '6px', xl: '8px' },
  },

  semantic: {
    primary: cobalt,

    // 다크 배경에서 1px 링은 보이지 않는다. 키보드 이동이 어디 있는지 알 수 있어야 한다.
    focusRing: { width: '2px', style: 'solid', color: '{primary.color}', offset: '1px', shadow: 'none' },

    // 폼 필드를 한 단계 더 조인다. 기본 컨트롤 높이가 32px이 되도록 맞춘 값이다
    // (13px × 1.45 ≈ 19px + 상하 패딩 10px + 테두리 2px). 참고 사이트의 select가 40px인데
    // 그쪽은 필터가 세로로 서너 개뿐이고, 여기는 칩 바에 스무 개까지 들어간다.
    formField: {
      paddingX: '0.5rem',
      paddingY: '0.3125rem',
      fontSize: '0.8125rem',
      sm: { fontSize: '0.75rem', paddingX: '0.4375rem', paddingY: '0.1875rem' },
      lg: { fontSize: '0.9375rem', paddingX: '0.6875rem', paddingY: '0.4375rem' },
    },

    list: {
      padding: '0.25rem',
      option: { padding: '0.375rem 0.5rem' },
      optionGroup: { padding: '0.5rem 0.5rem 0.25rem', fontWeight: '600' },
    },

    colorScheme: {
      dark: {
        surface: ink,

        // 순백은 다크에서 눈이 아프다. 본문은 한 단계 내리고 muted는 확실히 떨어뜨린다.
        //
        // 본문/muted 두 단계뿐이라 위계가 안 섰다. 티커·시장코드·메타처럼 "있어야 하지만
        // 읽을 차례는 마지막"인 텍스트가 muted와 같은 밝기로 나와 표가 평평해 보였다.
        // 3순위는 `surface.500`이고 쓰는 쪽은 `main.css`의 `.text-tertiary` 유틸리티다.
        text: {
          color: '#dce3ed',
          hoverColor: '#f2f5f9',
          mutedColor: '{surface.400}',
          hoverMutedColor: '{surface.300}',
        },

        content: {
          background: '{surface.900}',
          hoverBackground: '{surface.800}',
          borderColor: '{surface.700}',
        },

        // 입력은 패널(900)보다 어둡다(950). 눌린 자리로 읽혀 어디를 채우면 되는지가 먼저 보인다.
        formField: {
          background: '{surface.950}',
          filledBackground: '{surface.800}',
          disabledBackground: '{surface.800}',
          borderColor: '{surface.700}',
          hoverBorderColor: '{surface.600}',
          focusBorderColor: '{primary.color}',
          color: '{text.color}',
          disabledColor: '{surface.500}',
          placeholderColor: '{surface.500}',
          invalidBorderColor: '{red.400}',
          invalidPlaceholderColor: '{red.400}',
          iconColor: '{surface.400}',
          shadow: 'none',
        },

        // 떠 있는 것은 패널보다 밝고 그림자가 깊다. 다크에서 그림자만으로는 층이 안 보인다.
        overlay: {
          select: { background: '{surface.850}', borderColor: '{surface.600}', shadow: '0 12px 28px -8px rgba(0,0,0,0.75)' },
          popover: { background: '{surface.850}', borderColor: '{surface.600}', shadow: '0 12px 28px -8px rgba(0,0,0,0.75)' },
          modal: { background: '{surface.850}', borderColor: '{surface.600}', shadow: '0 24px 56px -12px rgba(0,0,0,0.8)' },
        },

        list: {
          option: {
            focusBackground: '{surface.800}',
            selectedBackground: 'color-mix(in srgb, {primary.400}, transparent 82%)',
            selectedFocusBackground: 'color-mix(in srgb, {primary.400}, transparent 74%)',
            selectedColor: '{primary.300}',
            selectedFocusColor: '{primary.200}',
          },
        },

        navigation: {
          item: { focusBackground: '{surface.800}', activeBackground: '{surface.800}' },
        },
      },
    },
  },

  components: {
    /**
     * 표가 이 화면의 본체다. 줄무늬 대신 hairline으로 행을 가른다 — 다크에서 줄무늬는
     * 얼룩으로 보이고, 컬럼이 열 개를 넘으면 세로 정렬을 오히려 방해한다.
     */
    datatable: {
      headerCell: {
        background: '{surface.800}',
        borderColor: '{surface.700}',
        color: '{text.muted.color}',
        sm: { padding: '0.375rem 0.625rem' },
      },
      // 컬럼 머리글은 읽는 것이 아니라 짚는 것이다. 본문보다 작고 어두워야 값이 먼저 보인다.
      // 참고 사이트가 11px/500/50% 투명도로 두는 자리다.
      columnTitle: { fontWeight: '600' },
      bodyCell: { borderColor: '{surface.700}', sm: { padding: '0.25rem 0.625rem' } },
      row: { hoverBackground: '{surface.800}', stripedBackground: '{surface.900}' },
      footerCell: { background: '{surface.800}', borderColor: '{surface.700}' },
      header: { background: 'transparent', borderColor: '{surface.700}', borderWidth: '0 0 1px 0' },
      paginatorTop: { borderWidth: '0' },
      paginatorBottom: { borderWidth: '1px 0 0 0' },
      sortIcon: { color: '{surface.500}', hoverColor: '{primary.color}' },
      rowToggleButton: { hoverBackground: '{surface.700}', selectedHoverBackground: '{surface.700}' },
    },

    button: {
      root: {
        label: { fontWeight: '600' },
        // IMPORTANT — 버튼의 기본 글자 크기는 **여기서 정할 수 없다.** Aura의 button 토큰에
        // root.fontSize가 없고(타입이 거부한다) 컴포넌트 CSS가 1rem을 직접 적는다. 그래서
        // `main.css`의 `:root .p-button` 규칙이 그 자리를 맡는다 — 지우면 16px로 돌아간다.
        // 아이콘 버튼이 칩 바와 표 머리글에 붙는다. 기본 2.5rem이면 줄 높이를 이것이 정하게 된다.
        sm: { iconOnlyWidth: '1.75rem' },
      },
    },

    tag: {
      root: { fontSize: '0.75rem', fontWeight: '600', padding: '0.1875rem 0.4375rem' },
      colorScheme: {
        dark: {
          // IMPORTANT — Aura 기본값은 orange다. 지지·저항 패널의 주황과 같은 색이 되어
          // "판정 불가"와 "매매 신호 아님"이 구별되지 않는다. amber로 밀어 이격시킨다.
          warn: { background: 'color-mix(in srgb, {amber.400}, transparent 82%)', color: '{amber.300}' },
          success: { background: 'color-mix(in srgb, {emerald.400}, transparent 82%)', color: '{emerald.300}' },
          danger: { background: 'color-mix(in srgb, {red.400}, transparent 82%)', color: '{red.300}' },
          secondary: { background: '{surface.800}', color: '{surface.300}' },
        },
      },
    },

    /**
     * `SelectButton`(판정 방식·비교 대상)의 바탕.
     *
     * Aura 다크 기본값은 컨테이너 배경이 `{surface.950}`인데 그것이 곧 캔버스 색이라, 고르지
     * **않은** 항목이 경계 없는 맨 텍스트로 보였다. 두 항목 중 하나만 버튼처럼 보이면
     * 나머지가 고를 수 있는 것인지 알 수 없다.
     */
    togglebutton: {
      colorScheme: {
        dark: {
          root: {
            background: '{surface.900}',
            checkedBackground: '{surface.900}',
            hoverBackground: '{surface.900}',
            borderColor: '{surface.700}',
            checkedBorderColor: '{surface.700}',
          },
          content: { checkedBackground: '{surface.700}' },
        },
      },
    },

    message: {
      content: { padding: '0.625rem 0.875rem' },
      text: { fontSize: '0.8125rem', fontWeight: '400' },
    },

    skeleton: { root: { background: '{surface.800}', animationBackground: 'rgba(255,255,255,0.06)' } },

    toast: { root: { borderWidth: '1px' } },
  },
})
