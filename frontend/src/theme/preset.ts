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

/** 잉크블루 램프. 900이 패널, 950이 캔버스와 입력 배경, 700이 hairline이다. */
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
    borderRadius: { none: '0', xs: '2px', sm: '3px', md: '5px', lg: '7px', xl: '10px' },
  },

  semantic: {
    primary: cobalt,

    // 다크 배경에서 1px 링은 보이지 않는다. 키보드 이동이 어디 있는지 알 수 있어야 한다.
    focusRing: { width: '2px', style: 'solid', color: '{primary.color}', offset: '1px', shadow: 'none' },

    // 폼 필드를 한 단계 조인다. 조건 한 줄에 컨트롤이 여섯 개까지 들어가는 화면이다.
    formField: {
      paddingX: '0.625rem',
      paddingY: '0.4375rem',
      sm: { fontSize: '0.8125rem', paddingX: '0.5rem', paddingY: '0.3125rem' },
      lg: { fontSize: '1.0625rem', paddingX: '0.75rem', paddingY: '0.5625rem' },
    },

    list: {
      padding: '0.25rem',
      option: { padding: '0.4375rem 0.625rem' },
      optionGroup: { padding: '0.4375rem 0.625rem', fontWeight: '600' },
    },

    colorScheme: {
      dark: {
        surface: ink,

        // 순백은 다크에서 눈이 아프다. 본문은 한 단계 내리고 muted는 확실히 떨어뜨린다.
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
          select: { background: '#161d2b', borderColor: '{surface.600}', shadow: '0 12px 28px -8px rgba(0,0,0,0.75)' },
          popover: { background: '#161d2b', borderColor: '{surface.600}', shadow: '0 12px 28px -8px rgba(0,0,0,0.75)' },
          modal: { background: '#161d2b', borderColor: '{surface.600}', shadow: '0 24px 56px -12px rgba(0,0,0,0.8)' },
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
        sm: { padding: '0.5rem 0.75rem' },
      },
      columnTitle: { fontWeight: '600' },
      bodyCell: { borderColor: '{surface.700}', sm: { padding: '0.4375rem 0.75rem' } },
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
        // 아이콘 버튼이 조건 행마다 붙는다. 기본 2.5rem이면 행 높이를 이것이 정하게 된다.
        sm: { iconOnlyWidth: '1.875rem' },
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

    message: {
      content: { padding: '0.625rem 0.875rem' },
      text: { fontSize: '0.8125rem', fontWeight: '400' },
    },

    skeleton: { root: { background: '{surface.800}', animationBackground: 'rgba(255,255,255,0.06)' } },

    toast: { root: { borderWidth: '1px' } },
  },
})
