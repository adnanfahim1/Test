<?php
/**
 * Landing page markup (matches reference/index.html <body>).
 *
 * @package CodexaLanding
 *
 * @var array $cdx Settings from Codexa_Settings::all().
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

$cdx_img = static function ( $path ) {
	return esc_url( Codexa_Landing::asset( 'img/' . $path ) );
};

$cdx_brands = array(
	array( 'urban.png', __( 'Urban', 'codexa-landing' ), 142, 43 ),
	array( 'polar.png', __( 'Polar Ice Cream', 'codexa-landing' ), 119, 106 ),
	array( 'dancake.png', __( 'Dan Cake', 'codexa-landing' ), 180, 92 ),
	array( 'sfm.png', __( 'Sublime Facilities Management (SFM)', 'codexa-landing' ), 182, 80 ),
	array( 'orbit.png', __( 'Orbit Technologies', 'codexa-landing' ), 184, 68 ),
	array( 'rtm.png', __( 'Route to Market Ltd.', 'codexa-landing' ), 148, 111 ),
	array( 'interstoff.png', __( 'Interstoff', 'codexa-landing' ), 205, 49 ),
	array( 'ipr.png', __( 'IPR Asset Management', 'codexa-landing' ), 148, 90 ),
	array( 'auleek.png', __( 'Auleek', 'codexa-landing' ), 75, 101 ),
	array( 'cocovalt.png', __( 'Cocovalt — Pandughar Foods Ltd.', 'codexa-landing' ), 139, 143 ),
	array( 'vybe.png', __( 'Vybe', 'codexa-landing' ), 144, 39 ),
	array( 'codexa.png', __( 'Codexa', 'codexa-landing' ), 168, 34 ),
	array( 'agro-corp.png', __( 'Pandughar Agro Corporation', 'codexa-landing' ), 180, 109 ),
	array( 'agro-foods.png', __( 'Pandughar Agro & Foods Ltd.', 'codexa-landing' ), 157, 99 ),
	array( 'foundation.png', __( 'Pandughar Hasina Khanom Foundation', 'codexa-landing' ), 247, 76 ),
);

$cdx_pulse   = (float) $cdx['pulse'] > 0 ? (float) $cdx['pulse'] : 4;
$cdx_liquid  = min( 2, max( 0, (float) $cdx['liquid'] ) );
$cdx_url     = (string) $cdx['learn_more_url'];
$cdx_new_tab = ! empty( $cdx['learn_more_new_tab'] );
$cdx_pg_alt  = __( 'A Concern of Pandughar Group', 'codexa-landing' );
?>
<div class="cdx-page" style="--cdx-pulse:<?php echo esc_attr( $cdx_pulse ); ?>s" data-cdx-liquid="<?php echo esc_attr( $cdx_liquid ); ?>">

	<!-- ===================== 1. HERO (white theme) ===================== -->
	<section class="cdx-hero" data-cdx-hero>
		<div class="cdx-hero__blob cdx-hero__blob--a" aria-hidden="true"></div>
		<div class="cdx-hero__blob cdx-hero__blob--b" aria-hidden="true"></div>
		<div class="cdx-hero__blob cdx-hero__blob--c" aria-hidden="true"></div>

		<div class="cdx-hero__orbit" data-cdx-hero-parallax aria-hidden="true">
			<div class="cdx-ring cdx-ring--600"></div>
			<div class="cdx-ring cdx-ring--820"></div>
			<div class="cdx-ripple"></div>
			<div class="cdx-ripple cdx-ripple--late"></div>
			<div class="cdx-sweep-ring"></div>
			<div class="cdx-orbiter cdx-orbiter--inner">
				<span class="cdx-node cdx-node--top"></span>
				<span class="cdx-node cdx-node--bottom"></span>
			</div>
			<div class="cdx-orbiter cdx-orbiter--outer">
				<span class="cdx-node cdx-node--left"></span>
				<span class="cdx-node cdx-node--small"></span>
			</div>
			<div class="cdx-orb"></div>
		</div>

		<div class="cdx-hero__fade" aria-hidden="true"></div>

		<header class="cdx-hero__bar">
			<div class="cdx-pill">
				<img src="<?php echo $cdx_img( 'pg-concern-color.png' ); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped ?>" alt="<?php echo esc_attr( $cdx_pg_alt ); ?>" width="1600" height="133" loading="eager" decoding="async">
			</div>
		</header>

		<div class="cdx-hero__logo">
			<h1 class="cdx-visually-hidden"><?php esc_html_e( 'Codexa', 'codexa-landing' ); ?></h1>
			<img src="<?php echo $cdx_img( 'codexa-logo-blue.png' ); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped ?>" alt="<?php esc_attr_e( 'Codexa', 'codexa-landing' ); ?>" width="1348" height="264" loading="eager" fetchpriority="high">
		</div>
	</section>

	<!-- ===================== 2. ABOUT ===================== -->
	<section class="cdx-about" id="about">
		<div class="cdx-about__inner">
			<div class="cdx-about__head">
				<span class="cdx-eyebrow"><?php esc_html_e( '01 — About', 'codexa-landing' ); ?></span>
				<h2 class="cdx-about__title"><img src="<?php echo $cdx_img( 'pg-concern-color.png' ); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped ?>" alt="<?php echo esc_attr( $cdx_pg_alt ); ?>" width="1600" height="133" loading="lazy" decoding="async"></h2>
				<div class="cdx-divider" aria-hidden="true"></div>
			</div>

			<div class="cdx-about__copy">
				<p><?php esc_html_e( 'Pandughar Group is a conglomerate with a diverse portfolio spanning real estate, ready-made garments, composite textiles, consumer goods, IT, financial services, and agricultural products. The Group’s journey began in 1995 with Urban Design & Development Limited, a well-recognized real estate player in Dhaka. Over the past three decades, it has grown significantly, now employing more than 20,000 dedicated professionals.', 'codexa-landing' ); ?></p>
				<p><?php esc_html_e( 'Central to our growth ambition is our deep commitment to our community responsibilities through our Pandughar Hasina Khanom Foundation. The Foundation supports community development by providing free, quality education to over 500 rural students, a monthly food program for more than 2,500 families, medical assistance, and access to safe drinking water, among other initiatives.', 'codexa-landing' ); ?></p>
				<p><?php esc_html_e( 'Rooted in strong ethical principles and a people-first culture, Pandughar Group contributes to the economic development of Bangladesh while building a sustainable future for its stakeholders.', 'codexa-landing' ); ?></p>
			</div>

			<div class="cdx-brands">
				<ul class="cdx-brands__grid">
					<?php foreach ( $cdx_brands as $cdx_brand ) : ?>
						<li><img src="<?php echo $cdx_img( 'brands/' . $cdx_brand[0] ); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped ?>" alt="<?php echo esc_attr( $cdx_brand[1] ); ?>" width="<?php echo (int) $cdx_brand[2]; ?>" height="<?php echo (int) $cdx_brand[3]; ?>" loading="lazy" decoding="async"></li>
					<?php endforeach; ?>
				</ul>
			</div>

			<?php if ( '' !== $cdx_url ) : ?>
			<div class="cdx-cta" data-cdx-magnet>
				<a class="cdx-btn" href="<?php echo esc_url( $cdx_url ); ?>"<?php echo $cdx_new_tab ? ' target="_blank" rel="noopener noreferrer"' : ''; ?> data-cdx-magnet-target>
					<span><?php esc_html_e( 'Learn more', 'codexa-landing' ); ?></span>
					<?php if ( $cdx_new_tab ) : ?>
						<span class="cdx-visually-hidden"><?php esc_html_e( '(opens in a new tab)', 'codexa-landing' ); ?></span>
					<?php endif; ?>
					<span class="cdx-btn__arrow" aria-hidden="true">
						<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="M13 6l6 6-6 6"/></svg>
					</span>
				</a>
			</div>
			<?php endif; ?>
		</div>
	</section>

	<!-- ===================== 3. COMING SOON (liquid glass) ===================== -->
	<section class="cdx-soon" id="coming-soon" data-cdx-soon>
		<div class="cdx-soon__inner" data-cdx-soon-box>
			<div class="cdx-soon__meta">
				<img src="<?php echo $cdx_img( 'pg-concern-white.png' ); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped ?>" alt="<?php echo esc_attr( $cdx_pg_alt ); ?>" width="1600" height="126" loading="lazy" decoding="async">
				<span><?php echo esc_html( Codexa_Settings::copyright() ); ?></span>
			</div>
			<img class="cdx-soon__logo" src="<?php echo $cdx_img( 'codexa-logo-white.png' ); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped ?>" alt="<?php esc_attr_e( 'Codexa', 'codexa-landing' ); ?>" width="1348" height="264" loading="lazy" decoding="async">

			<h2 class="cdx-soon__title" data-cdx-soon-title><?php esc_html_e( 'COMING', 'codexa-landing' ); ?><br><?php esc_html_e( 'SOON', 'codexa-landing' ); ?><span class="cdx-dot">.</span><span class="cdx-dot">.</span><span class="cdx-dot">.</span></h2>

			<!-- Glass panes: base geometry lives in inline styles; JS animates them -->
			<div class="cdx-pane" data-cdx-pane="0" style="left:60px;top:120px;width:460px;height:460px">
				<div class="cdx-pane__liquid"><div class="cdx-glass"><div class="cdx-glass__shine"></div><div class="cdx-glass__sweep"></div></div></div>
			</div>
			<div class="cdx-pane" data-cdx-pane="1" style="left:420px;top:200px;width:540px;height:420px">
				<div class="cdx-pane__liquid" style="animation-delay:-3s"><div class="cdx-glass"><div class="cdx-glass__shine"></div><div class="cdx-glass__sweep" style="animation-delay:-3s"></div></div></div>
			</div>
			<div class="cdx-pane" data-cdx-pane="2" style="left:180px;top:420px;width:440px;height:440px">
				<div class="cdx-pane__liquid" style="animation-delay:-6s"><div class="cdx-glass"><div class="cdx-glass__shine"></div><div class="cdx-glass__sweep" style="animation-delay:-6s"></div></div></div>
			</div>

			<!-- Codexa "O" loader: fills as a progress ring once the panes have moved away -->
			<div class="cdx-loader" role="status" aria-label="<?php esc_attr_e( 'Loading', 'codexa-landing' ); ?>" data-cdx-loader>
				<svg viewBox="0 0 200 200" aria-hidden="true">
					<path class="cdx-loader__track" d="M46.38 145 A70 70 0 1 1 153.62 145"/>
					<path class="cdx-loader__ring" data-cdx-loader-ring d="M46.38 145 A70 70 0 1 1 153.62 145"/>
					<rect class="cdx-loader__bar" data-cdx-loader-bar x="75" y="168" width="50" height="12"/>
				</svg>
			</div>
		</div>
	</section>

</div>
