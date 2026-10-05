<?php
/**
 * Plugin Name:       Cocovalt Landing
 * Description:       Adds the "Cocovalt Landing" page template: the full-bleed Cocovalt / Pandughar Group landing page, with its text editable in wp-admin. Works with any theme.
 * Version:           1.0.0
 * Requires at least: 6.0
 * Requires PHP:      7.4
 * License:           GPL-2.0-or-later
 * License URI:       https://www.gnu.org/licenses/gpl-2.0.html
 * Text Domain:       cocovalt-landing
 *
 * @package CocovaltLanding
 */

defined( 'ABSPATH' ) || exit;

define( 'COCOVALT_LANDING_VERSION', '1.0.0' );
define( 'COCOVALT_LANDING_DIR', plugin_dir_path( __FILE__ ) );
define( 'COCOVALT_LANDING_URL', plugin_dir_url( __FILE__ ) );

/** Value stored in a page's `_wp_page_template` meta when this template is chosen. */
define( 'COCOVALT_LANDING_TEMPLATE', 'cocovalt-landing.php' );

require_once COCOVALT_LANDING_DIR . 'includes/content.php';

if ( is_admin() ) {
	require_once COCOVALT_LANDING_DIR . 'includes/admin.php';
}

/* -------------------------------------------------------------------------
 * Page template
 * ---------------------------------------------------------------------- */

/**
 * List the template in the page editor's Template dropdown (classic and block themes).
 *
 * @param array $templates Template file => label.
 * @return array
 */
function cocovalt_landing_register_template( $templates ) {
	$templates[ COCOVALT_LANDING_TEMPLATE ] = __( 'Cocovalt Landing', 'cocovalt-landing' );
	return $templates;
}
add_filter( 'theme_page_templates', 'cocovalt_landing_register_template' );

/**
 * Whether the current request is a page using the Cocovalt Landing template.
 *
 * @return bool
 */
function cocovalt_landing_is_active() {
	return is_page() && COCOVALT_LANDING_TEMPLATE === get_page_template_slug( get_queried_object_id() );
}

/**
 * Serve the plugin's blank template instead of the theme's.
 *
 * @param string $template Template path chosen by WordPress.
 * @return string
 */
function cocovalt_landing_template_include( $template ) {
	if ( ! cocovalt_landing_is_active() ) {
		return $template;
	}

	// Block themes hook a viewport tag and skip link into the block-template
	// canvas we're replacing; the template prints its own viewport tag.
	remove_action( 'wp_head', '_block_template_viewport_meta_tag', 0 );
	remove_action( 'wp_enqueue_scripts', 'wp_enqueue_block_template_skip_link' );
	remove_action( 'wp_footer', 'the_block_template_skip_link' );

	// Exactly one <title>, whether the theme is a block theme, a classic theme
	// with title-tag support, or an old theme without it. SEO plugins manage
	// their own title output, so leave theirs alone.
	if ( ! cocovalt_landing_seo_plugin_active() ) {
		remove_action( 'wp_head', '_block_template_render_title_tag', 1 );
		remove_action( 'wp_head', '_wp_render_title_tag', 1 );
		add_action( 'wp_head', 'cocovalt_landing_render_title', 1 );
	}

	return COCOVALT_LANDING_DIR . 'templates/landing.php';
}
add_filter( 'template_include', 'cocovalt_landing_template_include', 99 );

/** Same output as core's _wp_render_title_tag(), without the theme-support check. */
function cocovalt_landing_render_title() {
	echo '<title>' . wp_get_document_title() . '</title>' . "\n"; // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- escaped by wp_get_document_title() / cocovalt_landing_document_title().
}

/**
 * @param string[] $classes Body classes.
 * @return string[]
 */
function cocovalt_landing_body_class( $classes ) {
	if ( cocovalt_landing_is_active() ) {
		$classes[] = 'cocovalt-landing-page';
	}
	return $classes;
}
add_filter( 'body_class', 'cocovalt_landing_body_class' );

/* -------------------------------------------------------------------------
 * Assets (only on the landing template)
 * ---------------------------------------------------------------------- */

function cocovalt_landing_enqueue_assets() {
	if ( ! cocovalt_landing_is_active() ) {
		return;
	}

	wp_enqueue_style(
		'cocovalt-landing-fonts',
		'https://fonts.googleapis.com/css2?family=Jost:wght@300;400;500&family=Barlow+Condensed:wght@600&display=swap',
		array(),
		null // phpcs:ignore WordPress.WP.EnqueuedResourceParameters.MissingVersion -- a ?ver= query arg would break the Google Fonts URL cache.
	);
	wp_enqueue_style(
		'cocovalt-landing',
		COCOVALT_LANDING_URL . 'assets/css/cocovalt.css',
		array( 'cocovalt-landing-fonts' ),
		COCOVALT_LANDING_VERSION
	);
	wp_enqueue_script(
		'cocovalt-landing',
		COCOVALT_LANDING_URL . 'assets/js/cocovalt.js',
		array(),
		COCOVALT_LANDING_VERSION,
		true
	);
}
add_action( 'wp_enqueue_scripts', 'cocovalt_landing_enqueue_assets' );

/**
 * The template has no theme header or footer, so the theme's stylesheets only
 * add risk (rules on button, img, a, p, h2, body padding, containers). Drop them,
 * plus core block/global styles, on this template only.
 *
 * Disable with: add_filter( 'cocovalt_landing_isolate_theme_styles', '__return_false' );
 */
function cocovalt_landing_isolate_theme_styles() {
	if ( ! cocovalt_landing_is_active() || ! apply_filters( 'cocovalt_landing_isolate_theme_styles', true ) ) {
		return;
	}

	$styles     = wp_styles();
	$theme_uris = array_unique( array( get_template_directory_uri(), get_stylesheet_directory_uri() ) );

	foreach ( $styles->queue as $handle ) {
		$src = isset( $styles->registered[ $handle ] ) ? (string) $styles->registered[ $handle ]->src : '';
		foreach ( $theme_uris as $uri ) {
			if ( '' !== $src && 0 === strpos( $src, $uri ) ) {
				wp_dequeue_style( $handle );
			}
		}
	}

	foreach ( array( 'global-styles', 'wp-block-library', 'wp-block-library-theme', 'classic-theme-styles' ) as $handle ) {
		wp_dequeue_style( $handle );
	}
	remove_action( 'wp_footer', 'wp_enqueue_global_styles', 1 );
}
add_action( 'wp_enqueue_scripts', 'cocovalt_landing_isolate_theme_styles', PHP_INT_MAX );

/**
 * @param array  $urls          URLs to print for resource hints.
 * @param string $relation_type The relation type the URLs are printed for.
 * @return array
 */
function cocovalt_landing_resource_hints( $urls, $relation_type ) {
	if ( 'preconnect' === $relation_type && cocovalt_landing_is_active() ) {
		$urls[] = 'https://fonts.googleapis.com';
		$urls[] = array(
			'href'        => 'https://fonts.gstatic.com',
			'crossorigin' => 'anonymous',
		);
	}
	return $urls;
}
add_filter( 'wp_resource_hints', 'cocovalt_landing_resource_hints', 10, 2 );

/* -------------------------------------------------------------------------
 * Title, meta description, Open Graph, favicon
 * ---------------------------------------------------------------------- */

/**
 * Whether a dedicated SEO plugin is handling title/description/OG tags.
 *
 * @return bool
 */
function cocovalt_landing_seo_plugin_active() {
	$active = defined( 'WPSEO_VERSION' )          // Yoast SEO.
		|| defined( 'RANK_MATH_VERSION' )         // Rank Math.
		|| defined( 'AIOSEO_VERSION' )            // All in One SEO.
		|| defined( 'SEOPRESS_VERSION' );         // SEOPress.

	return (bool) apply_filters( 'cocovalt_landing_seo_plugin_active', $active );
}

/**
 * @param string $title Title WordPress would use; empty to let it build one.
 * @return string
 */
function cocovalt_landing_document_title( $title ) {
	if ( cocovalt_landing_is_active() && ! cocovalt_landing_seo_plugin_active() ) {
		$custom = cocovalt_landing_get( 'seo_title' );
		if ( '' !== $custom ) {
			// A non-empty value here skips core's own escaping, so escape it the same way.
			return esc_html( $custom );
		}
	}
	return $title;
}
add_filter( 'pre_get_document_title', 'cocovalt_landing_document_title', 20 );

function cocovalt_landing_head_tags() {
	if ( ! cocovalt_landing_is_active() ) {
		return;
	}

	if ( ! cocovalt_landing_seo_plugin_active() ) {
		$title       = wp_get_document_title();
		$description = cocovalt_landing_get( 'seo_description' );
		$url         = get_permalink( get_queried_object_id() );
		$image       = COCOVALT_LANDING_URL . 'assets/img/cocovalt-logo-original.png';

		if ( '' !== $description ) {
			printf( '<meta name="description" content="%s">' . "\n", esc_attr( $description ) );
		}
		echo '<meta property="og:type" content="website">' . "\n";
		printf( '<meta property="og:site_name" content="%s">' . "\n", esc_attr( get_bloginfo( 'name' ) ) );
		printf( '<meta property="og:title" content="%s">' . "\n", esc_attr( $title ) );
		if ( '' !== $description ) {
			printf( '<meta property="og:description" content="%s">' . "\n", esc_attr( $description ) );
		}
		printf( '<meta property="og:url" content="%s">' . "\n", esc_url( $url ) );
		printf( '<meta property="og:image" content="%s">' . "\n", esc_url( $image ) );
		echo '<meta property="og:image:width" content="1864">' . "\n";
		echo '<meta property="og:image:height" content="2186">' . "\n";
		printf( '<meta property="og:image:alt" content="%s">' . "\n", esc_attr__( 'Cocovalt — Pandughar Foods Ltd.', 'cocovalt-landing' ) );
		echo '<meta name="twitter:card" content="summary">' . "\n";
	}

	// A Site Icon set under Appearance → Customize → Site Identity takes precedence.
	if ( ! has_site_icon() ) {
		$img = COCOVALT_LANDING_URL . 'assets/img/';
		printf( '<link rel="icon" href="%s" sizes="32x32">' . "\n", esc_url( $img . 'favicon-32.png' ) );
		printf( '<link rel="icon" href="%s" sizes="192x192">' . "\n", esc_url( $img . 'favicon-192.png' ) );
		printf( '<link rel="apple-touch-icon" href="%s">' . "\n", esc_url( $img . 'apple-touch-icon.png' ) );
	}
}
add_action( 'wp_head', 'cocovalt_landing_head_tags', 5 );

/* -------------------------------------------------------------------------
 * Helpers used by the template
 * ---------------------------------------------------------------------- */

/**
 * Build an <img> for a bundled image, wrapped in <picture> with a WebP source when one exists.
 *
 * @param string $file  File name inside assets/img/.
 * @param array  $attrs Attribute => value for the <img>. Values are escaped here.
 * @return string Escaped HTML.
 */
function cocovalt_landing_image( $file, array $attrs = array() ) {
	$base = COCOVALT_LANDING_URL . 'assets/img/';
	$html = '<img src="' . esc_url( $base . $file ) . '"';
	foreach ( $attrs as $name => $value ) {
		$html .= ' ' . esc_attr( $name ) . '="' . esc_attr( $value ) . '"';
	}
	$html .= '>';

	$webp = preg_replace( '/\.(png|jpe?g)$/i', '.webp', $file );
	if ( $webp !== $file && file_exists( COCOVALT_LANDING_DIR . 'assets/img/' . $webp ) ) {
		$html = '<picture><source type="image/webp" srcset="' . esc_url( $base . $webp ) . '">' . $html . '</picture>';
	}
	return $html;
}
