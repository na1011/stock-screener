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
/**
 * 시세 등락의 색 참조. `contrast.spec.ts`가 **이 상수를** 읽는다.
 *
 * IMPORTANT — 검사 쪽에 hex를 옮겨 적으면 프리셋을 바꿔도 검사가 통과한다. `cobalt`·`ink`를
 * import하는 것과 같은 이유다.
 */
export const QUOTE_RISE = '{red.400}'
export const QUOTE_FALL = '{sky.400}'

export const ink = {
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

/**
 * 코발트. 다크에서 실제로 쓰이는 것은 400(글자·테두리)·500(토널 테두리)·600(채운 버튼)이다.
 *
 * `export`인 이유는 `contrast.spec.ts`가 이 값으로 대비비를 계산하기 때문이다 — 램프를 옮겨
 * 적으면 검사가 실제 화면이 아니라 사본을 검사하게 된다.
 */
export const cobalt = {
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

    /**
     * 컨트롤 높이. **기본 36px, `sm` 29px**을 목표로 맞춘 값이다.
     *
     * IMPORTANT — 이 계산은 `main.css`가 `:root .p-*`에 **`line-height`를 명시한다는 전제** 위에
     * 선다. PrimeVue도 이 preset도 폼 컨트롤에 line-height를 걸지 않아서, `<input>`·`<button>`은
     * UA의 `normal`을 쓰고 `.p-select-label`은 `<span>`이라 body의 1.45를 상속했다 — **같은
     * 글자 크기인데 Select와 InputNumber의 높이가 서로 달랐다.** 그 둘은 조건 편집기에서
     * 세로로 붙어 있는 자리다. 패딩만 만지면 그 어긋남이 그대로 확대된다.
     *
     * 실측(1440×900, Chrome): 이 값 이전의 기본 버튼은 32px이 아니라 **28px**이었다 — 예전
     * 주석이 근거로 든 "13px × 1.45"가 input·button에는 적용된 적이 없기 때문이다. 지금
     * 값으로는 기본 36px / `sm` 29px이고 아이콘 전용 버튼이 36×36 / 29×29로 정사각이다.
     *
     * 왜 36/29인가. 23px이던 `sm`은 WCAG 2.2의 최소 타깃 24×24에도 못 미쳤고(`ScreenerView`가
     * 좁은 폭에서만 그것을 보정하고 있었다), 기본과 `sm`의 격차 9px은 같은 줄에 섞였을 때
     * 눈에 띄게 어긋났다. 표 밀도는 이 토큰이 정하지 않는다 — `datatable`이 따로 갖는다.
     */
    formField: {
      paddingX: '0.5rem',
      paddingY: '0.5rem',
      fontSize: '0.8125rem',
      sm: { fontSize: '0.75rem', paddingX: '0.4375rem', paddingY: '0.3125rem' },
      lg: { fontSize: '0.9375rem', paddingX: '0.6875rem', paddingY: '0.625rem' },
    },

    list: {
      padding: '0.25rem',
      // 옵션 높이는 입력 높이를 따라간다. 입력만 키우면 열었을 때 목록이 상대적으로
      // 빽빽해 보이고, 손가락으로 고르는 자리에서 특히 좁다.
      option: { padding: '0.4375rem 0.5rem' },
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
          /**
           * 3순위. `surface.500`을 텍스트로 쓰던 것을 대체한다.
           *
           * IMPORTANT — `surface.500`(#616d82)은 카드 배경 위에서 **3.43:1**이라 본문 대비
           * 기준(4.5:1)에 못 미쳤다. 표면 램프의 한 칸을 글자에 빌려 쓴 것이 원인이다 —
           * 면과 글자는 필요한 밝기가 다르다. 이 값은 카드에서 4.8:1, 캔버스에서 5.2:1이고
           * muted(#8792a6, 5.7:1)보다는 확실히 어두워 3단계가 유지된다.
           */
          subtleColor: '#78859a',
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

        /**
         * 값 구간의 색조. `lib/band.ts`의 세 tone이 그대로 대응한다.
         *
         * IMPORTANT — 판정 3상태와 **같은 색을 쓴다.** 색을 나누는 대신 형태를 나눈 것이고,
         * 그 규칙은 ADR-0021의 "형태로 가른다" 절에 있다 — 판정은 `Tag` 알약, 값 구간은
         * 미터 막대와 숫자 색조다. 그리고 값 색조는 판정 `Tag`가 없는 **종목 상세에만** 둔다.
         *
         * 글자는 300, 막대는 400이다. 다크에서 400은 큰 면적일 때 눈이 아프고, 300은
         * 1~3px 막대로 쓰면 배경에 묻힌다.
         */
        tone: {
          favorableText: '{emerald.300}',
          favorableFill: '{emerald.400}',
          unfavorableText: '{red.300}',
          unfavorableFill: '{red.400}',
          neutralText: '{text.color}',
          neutralFill: '{surface.500}',
          track: '{surface.700}',
        },

        /**
         * 시세 등락(전일 대비)의 색. **한국 시장 관례라 상승이 빨강이다**(사용자 결정, ADR-0065).
         *
         * IMPORTANT — 위 `tone`과 **이름을 나눈 것이 요점이다.** 재사용하면 한 토큰이 두 뜻을
         * 겸하게 되고, ADR-0021 §검증 3-1(`rg -ln -- '--p-tone-'`)이 재는 채널 분리도 무너진다.
         *
         * 이 색이 치르는 대가는 ADR-0065가 적는다 — 같은 화면의 지표 카드에서 빨강이 "불리"를
         * 뜻하는데 여기서는 "상승"이고, `primary`가 코발트라 파랑도 둘이 된다. 형태로 가를 수
         * 없다(ADR-0021이 `▲`/`▼`에 색 주는 것을 금지한다). 대비는 `contrast.spec.ts`가 잰다.
         */
        quote: {
          riseText: QUOTE_RISE,
          fallText: QUOTE_FALL,
          flatText: '{text.muted.color}',
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
      /**
       * 버튼 강조 **3단**: 채움 > 토널 > 고스트. 화면당 채움은 하나다(ADR-0021).
       *
       * 예전에는 단이 **둘**이었다 — 채움과 "아무것도 없음". 윤곽·텍스트 버튼의 배경이 전부
       * `transparent`라 페이지 배경 그대로였고, 그래서 저장·불러오기 같은 부차 액션이 화면에서
       * 통째로 사라졌다. 다크에서 면 없는 버튼은 링크와 구별되지 않는다.
       *
       * IMPORTANT — `root.primary.color`를 반드시 명시한다. **Aura 다크 기본값이
       * `contrastColor: {surface.900}`인데 이 앱에서 그것은 `#121722`, 곧 카드·레일·바의
       * 배경색과 완전히 같은 hex다.** 라이트 스킴은 `#ffffff`를 하드코딩하고 다크만 참조로
       * 두기 때문에, `surface`를 커스텀 램프로 덮은 이 앱에서는 **다크만 조용히 깨진다.**
       * 지우면 채운 버튼의 글자가 다시 패널 배경색이 되는데 컴파일도 테스트도 통과한다.
       *
       * 배경을 `{primary.400}`에서 600으로 내리는 이유는 흰 글자를 얹기 위해서다 — 400 위의
       * 흰 글자는 2.99:1로 AA에 미달한다. 600 + 흰색은 5.33:1이고, 버튼 자체도 다섯 배경
       * 모두에서 3.13~3.65:1로 비텍스트 기준을 넘는다.
       *
       * `{surface.0}`가 흰색이다. 리터럴 `#fff`를 쓰지 않는 이유는 ADR-0021의 색 리터럴 금지다.
       */
      colorScheme: {
        dark: {
          root: {
            primary: {
              background: '{primary.600}',
              hoverBackground: '{primary.500}',
              activeBackground: '{primary.400}',
              borderColor: '{primary.600}',
              hoverBorderColor: '{primary.500}',
              activeBorderColor: '{primary.400}',
              color: '{surface.0}',
              hoverColor: '{surface.0}',
              activeColor: '{surface.0}',
            },
            /**
             * IMPORTANT — Aura 기본값은 orange다. ADR-0021이 주황을 지지·저항 참고정보
             * **전용**으로 못 박았고 `Tag`는 이미 amber로 이격시켰는데 `Button`만 따라가지
             * 않아, 결과가 낡으면(`ScreenerView`의 실행 버튼) 지지·저항과 같은 주황이 떴다.
             */
            warn: {
              background: '{amber.400}',
              hoverBackground: '{amber.300}',
              activeBackground: '{amber.200}',
              borderColor: '{amber.400}',
              hoverBorderColor: '{amber.300}',
              activeBorderColor: '{amber.200}',
              color: '{amber.950}',
              hoverColor: '{amber.950}',
              activeColor: '{amber.950}',
            },
          },

          /**
           * 토널 — filled와 outlined 사이의 중간 강조(M3의 `filled tonal`).
           *
           * 면을 주는 것이 요점이다. 테두리만 밝혀서는 안 된다 — 실제로 그렇게 고쳤다가
           * 측정 대비가 2.44에서 2.19로 **내려갔다.** 채도 높은 진파랑을 배경 쪽으로 섞으면
           * 명도 차가 줄기 때문이다. 지금은 테두리 `{primary.500}`이 배경 대비 4.27~4.98:1로
           * WCAG 1.4.11의 3:1을 넘는다.
           *
           * IMPORTANT — **쉬는 상태의 면은 여기서 줄 수 없다.** Aura의 `outlined` 토큰에
           * `background`가 없다(투명이 설계 전제라 타입이 거부한다). 그 한 줄만 `main.css`의
           * `:root .p-button-outlined` 규칙이 맡는다 — `--app-surface-raised`와 같이 `--p-*`를
           * 섞은 합성값이므로 ADR-0021의 "색은 preset에서만"을 어기지 않는다.
           */
          outlined: {
            primary: {
              hoverBackground: 'color-mix(in srgb, {primary.400}, transparent 80%)',
              activeBackground: 'color-mix(in srgb, {primary.400}, transparent 72%)',
              borderColor: '{primary.500}',
              color: '{primary.400}',
            },
            secondary: {
              hoverBackground: 'color-mix(in srgb, {surface.0}, transparent 90%)',
              activeBackground: 'color-mix(in srgb, {surface.0}, transparent 86%)',
              borderColor: '{surface.600}',
              color: '{surface.300}',
            },
            danger: {
              hoverBackground: 'color-mix(in srgb, {red.400}, transparent 80%)',
              activeBackground: 'color-mix(in srgb, {red.400}, transparent 72%)',
              borderColor: 'color-mix(in srgb, {red.400}, transparent 30%)',
              color: '{red.400}',
            },
          },

          /**
           * 고스트 — 쉬는 동안은 면이 없되 **hover는 반드시 보여야 한다.**
           *
           * IMPORTANT — Aura 기본값이 4% 틴트라 대비가 1.04~1.06, 즉 마우스를 올려도 아무
           * 일도 일어나지 않았다. 레일의 네이티브 `.app-list-button`이 hover를 더 잘 보여주는
           * 역전 상태였다.
           *
           * **불투명 표면 색(`{surface.800}`)을 쓰지 않는다.** 팝오버(`{surface.850}`)와 들린
           * 카드가 이미 그 값에 가까워 그 면들에서 hover가 사라진다(대비 1.04~1.05). 반투명
           * 오버레이만 다섯 배경 모두에서 일정하게 1.3배 안팎을 들어 올린다.
           */
          text: {
            primary: {
              hoverBackground: 'color-mix(in srgb, {primary.400}, transparent 84%)',
              activeBackground: 'color-mix(in srgb, {primary.400}, transparent 76%)',
            },
            secondary: {
              hoverBackground: 'color-mix(in srgb, {surface.0}, transparent 92%)',
              activeBackground: 'color-mix(in srgb, {surface.0}, transparent 88%)',
            },
            danger: {
              hoverBackground: 'color-mix(in srgb, {red.400}, transparent 84%)',
              activeBackground: 'color-mix(in srgb, {red.400}, transparent 76%)',
            },
          },
        },
      },
      root: {
        label: { fontWeight: '600' },
        // IMPORTANT — 버튼의 기본 글자 크기는 **여기서 정할 수 없다.** Aura의 button 토큰에
        // root.fontSize가 없고(타입이 거부한다) 컴포넌트 CSS가 1rem을 직접 적는다. 그래서
        // `main.css`의 `:root .p-button` 규칙이 그 자리를 맡는다 — 지우면 16px로 돌아간다.
        //
        // 아이콘 전용 버튼은 **정사각이어야 한다.** 폭을 높이와 같이 두지 않으면 칩 바와 표
        // 머리글에서 혼자 납작하거나 길쭉해 보인다. 기본값(Aura 2.5rem)도 함께 적는 이유는
        // 그 값이 새 기본 높이 36px과 4px 어긋나기 때문이다 — preset이 덮지 않아 그대로였다.
        iconOnlyWidth: '2.25rem',
        sm: { iconOnlyWidth: '1.8125rem' },
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
