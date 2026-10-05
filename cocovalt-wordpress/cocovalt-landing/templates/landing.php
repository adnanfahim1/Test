<?php
/**
 * Cocovalt Landing: full-bleed blank template (no theme header or footer).
 *
 * @package CocovaltLanding
 */

defined( 'ABSPATH' ) || exit;

$cv_button_label = cocovalt_landing_get( 'button_label' );
$cv_button_url   = cocovalt_landing_get( 'button_url' );
?><!doctype html>
<html <?php language_attributes(); ?>>
<head>
<meta charset="<?php bloginfo( 'charset' ); ?>">
<meta name="viewport" content="width=device-width, initial-scale=1">
<?php wp_head(); ?>
</head>
<body <?php body_class(); ?>>
<?php wp_body_open(); ?>

<main class="cocovalt-landing" id="cocovalt">
<!-- ============ 1 · HERO ============ -->
<section class="cv-hero" id="cv-hero">
	<div class="cv-hero__bg" aria-hidden="true">
		<div class="cv-hero__frame">
			<?php
			// phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- escaped in cocovalt_landing_image().
			echo cocovalt_landing_image(
				'hero-bg.jpg',
				array(
					'alt'           => '',
					'width'         => '1344',
					'height'        => '752',
					'fetchpriority' => 'high',
				)
			);
			?>
		</div>
	</div>
	<div class="cv-hero__dust" id="cv-dust" aria-hidden="true"></div>
	<div class="cv-hero__tint" aria-hidden="true"></div>
	<div class="cv-hero__vignette" aria-hidden="true"></div>
	<div class="cv-hero__glow" aria-hidden="true"></div>

	<div class="cv-stage" id="cv-stage">
		<svg class="cv-arrows" viewBox="0 0 1200 560" preserveAspectRatio="none" aria-hidden="true">
			<path class="cv-arrow-line" pathLength="1" d="M 438 312 Q 380 290 322 318"/>
			<path class="cv-arrow-head" d="M 334 306 L 322 318 L 338 322"/>
			<path class="cv-arrow-line" pathLength="1" d="M 762 312 Q 804 290 846 300"/>
			<path class="cv-arrow-head" d="M 834 291 L 846 300 L 833 307"/>
		</svg>

		<div class="cv-panel cv-panel--left" id="cv-panel-about">
			<p><?php echo cocovalt_landing_html( 'about' ); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- wp_kses'd. ?></p>
		</div>

		<div class="cv-stage__logo">
			<button class="cv-logo-btn" id="cv-logo-btn" type="button" aria-expanded="false" aria-controls="cv-panel-about cv-panel-group" aria-label="<?php esc_attr_e( 'Cocovalt — show details', 'cocovalt-landing' ); ?>">
				<span class="cv-tilt" id="cv-tilt">
					<span class="cv-pulse" aria-hidden="true"></span>
					<span class="cv-levitate">
						<?php
						// phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- escaped in cocovalt_landing_image().
						echo cocovalt_landing_image(
							'cocovalt-logo-cream.png',
							array(
								'alt'    => __( 'Cocovalt — Pandughar Foods Ltd.', 'cocovalt-landing' ),
								'width'  => '280',
								'height' => '329',
							)
						);
						?>
					</span>
				</span>
				<span class="cv-ground-shadow" aria-hidden="true"></span>
			</button>
			<p class="cv-hint" id="cv-hint" data-label-closed="<?php esc_attr_e( 'Click to explore', 'cocovalt-landing' ); ?>" data-label-open="<?php esc_attr_e( 'Click to close', 'cocovalt-landing' ); ?>"><?php esc_html_e( 'Click to explore', 'cocovalt-landing' ); ?></p>
		</div>

		<div class="cv-panel cv-panel--right" id="cv-panel-group">
			<?php
			// phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- escaped in cocovalt_landing_image().
			echo cocovalt_landing_image(
				'pandughar-concern-white.png',
				array(
					'alt'    => __( 'A Concern of Pandughar Group', 'cocovalt-landing' ),
					'width'  => '1600',
					'height' => '126',
				)
			);
			?>
		</div>

		<a class="cv-scroll-cue" href="#pandughar-group"><?php esc_html_e( 'Scroll', 'cocovalt-landing' ); ?>
			<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>
		</a>
	</div>

	<div class="cv-hero__fade" aria-hidden="true"></div>
</section>

<!-- ============ 2 · PANDUGHAR GROUP ============ -->
<section class="cv-group" id="pandughar-group">
	<div class="cv-group__inner">
		<div class="cv-group__head">
			<h2>
				<?php
				// phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- escaped in cocovalt_landing_image().
				echo cocovalt_landing_image(
					'pandughar-concern-color.png',
					array(
						'alt'      => __( 'A Concern of Pandughar Group', 'cocovalt-landing' ),
						'width'    => '1400',
						'height'   => '152',
						'loading'  => 'lazy',
						'decoding' => 'async',
					)
				);
				?>
			</h2>
			<div class="cv-divider" aria-hidden="true"></div>
		</div>
		<div class="cv-group__row">
			<div class="cv-group__text">
				<?php
				foreach ( array( 'group_lead', 'group_p2', 'group_p3' ) as $cv_key ) {
					$cv_html = cocovalt_landing_html( $cv_key );
					if ( '' !== trim( $cv_html ) ) {
						printf(
							'<p%s>%s</p>',
							'group_lead' === $cv_key ? ' class="cv-lead"' : '',
							$cv_html // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- wp_kses'd.
						);
					}
				}
				?>
				<?php if ( '' !== $cv_button_label ) : ?>
					<a class="cv-btn" href="<?php echo esc_url( '' !== $cv_button_url ? $cv_button_url : '#' ); ?>"><?php echo esc_html( $cv_button_label ); ?></a>
				<?php endif; ?>
			</div>
			<div class="cv-logo-box">
				<?php
				// phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- escaped in cocovalt_landing_image().
				echo cocovalt_landing_image(
					'pandughar-concerns-grid.jpg',
					array(
						'alt'      => __( 'Pandughar Group concerns: Urban, Polar Ice Cream, Dan Cake, Sublime Facilities Management, Orbit Technologies, Route to Market Ltd., Interstoff, IPR Asset Management, Auleek, Cocovalt, Vybe, Codexa, Pandughar Agro Corporation, Pandughar Agro & Foods Ltd., Pandughar Hasina Khanom Foundation', 'cocovalt-landing' ),
						'width'    => '1350',
						'height'   => '1400',
						'loading'  => 'lazy',
						'decoding' => 'async',
					)
				);
				?>
			</div>
		</div>
	</div>
</section>

<!-- ============ 3 · UNDER CONSTRUCTION ============ -->
<section class="cv-uc" aria-label="<?php esc_attr_e( 'Website under construction', 'cocovalt-landing' ); ?>">
	<div class="cv-tape">
		<div class="cv-tape__track" id="cv-tape-track" aria-hidden="true" data-text="<?php esc_attr_e( 'WEBSITE UNDER CONSTRUCTION', 'cocovalt-landing' ); ?>"></div>
	</div>
	<p class="cv-sr-only"><?php esc_html_e( 'Website under construction', 'cocovalt-landing' ); ?></p>
</section>
</main>

<?php wp_footer(); ?>
</body>
</html>
