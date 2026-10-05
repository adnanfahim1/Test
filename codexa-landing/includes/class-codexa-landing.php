<?php
/**
 * Hooks, assets, shortcode, page template and setup.
 *
 * @package CodexaLanding
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

/**
 * Main plugin class.
 */
class Codexa_Landing {

	const TEMPLATE    = 'codexa-landing-full-width.php';
	const SHORTCODE   = 'codexa_landing';
	const NOTICE_FLAG = 'codexa_landing_setup_notice';
	const PAGE_META   = '_codexa_landing_page';

	/**
	 * True when the current request shows the landing page.
	 *
	 * @var bool|null
	 */
	private static $is_landing = null;

	/**
	 * Register hooks.
	 */
	public static function init() {
		load_plugin_textdomain( 'codexa-landing', false, dirname( plugin_basename( CODEXA_LANDING_FILE ) ) . '/languages' );

		add_filter( 'theme_page_templates', array( __CLASS__, 'add_template' ) );
		add_filter( 'template_include', array( __CLASS__, 'template_include' ), 99 );
		add_shortcode( self::SHORTCODE, array( __CLASS__, 'shortcode' ) );

		add_action( 'wp_enqueue_scripts', array( __CLASS__, 'enqueue' ) );
		add_filter( 'script_loader_tag', array( __CLASS__, 'defer_script' ), 10, 2 );
		add_action( 'wp_head', array( __CLASS__, 'head' ), 2 );
		add_filter( 'pre_get_document_title', array( __CLASS__, 'document_title' ), 20 );
		add_filter( 'wp_resource_hints', array( __CLASS__, 'resource_hints' ), 10, 2 );

		if ( is_admin() ) {
			Codexa_Settings::init();
			add_action( 'admin_notices', array( __CLASS__, 'admin_notice' ) );
			add_action( 'admin_post_codexa_landing_setup', array( __CLASS__, 'handle_setup' ) );
			add_action( 'admin_post_codexa_landing_dismiss', array( __CLASS__, 'handle_dismiss' ) );
		}
	}

	/* ------------------------------------------------------------------
	 * Activation
	 * ------------------------------------------------------------------ */

	/**
	 * On activation, ask (via a notice) whether to create the page.
	 */
	public static function activate() {
		if ( ! self::find_landing_page() ) {
			update_option( self::NOTICE_FLAG, 1, false );
		}
	}

	/**
	 * On deactivation, drop the pending notice. Pages and settings are kept.
	 */
	public static function deactivate() {
		delete_option( self::NOTICE_FLAG );
	}

	/* ------------------------------------------------------------------
	 * Helpers
	 * ------------------------------------------------------------------ */

	/**
	 * URL of a file in assets/.
	 *
	 * @param string $path Path relative to assets/.
	 * @return string
	 */
	public static function asset( $path ) {
		return plugins_url( 'assets/' . ltrim( $path, '/' ), CODEXA_LANDING_FILE );
	}

	/**
	 * Cache-busting version for an asset file.
	 *
	 * @param string $path Path relative to assets/.
	 * @return string
	 */
	private static function ver( $path ) {
		$file = CODEXA_LANDING_DIR . 'assets/' . $path;
		return CODEXA_LANDING_VERSION . ( file_exists( $file ) ? '.' . filemtime( $file ) : '' );
	}

	/**
	 * Does this page use the full-width template?
	 *
	 * @param int|null $post_id Post ID (defaults to the queried object).
	 * @return bool
	 */
	public static function uses_template( $post_id = null ) {
		if ( null === $post_id ) {
			if ( ! is_singular() ) {
				return false;
			}
			$post_id = get_queried_object_id();
		}
		return $post_id && self::TEMPLATE === get_page_template_slug( $post_id );
	}

	/**
	 * Does this post contain the shortcode (in its content or Elementor data)?
	 *
	 * @param WP_Post|null $post Post.
	 * @return bool
	 */
	private static function has_shortcode_in( $post ) {
		if ( ! $post instanceof WP_Post ) {
			return false;
		}
		if ( has_shortcode( $post->post_content, self::SHORTCODE ) ) {
			return true;
		}
		$elementor = get_post_meta( $post->ID, '_elementor_data', true );
		return is_string( $elementor ) && false !== strpos( $elementor, '[' . self::SHORTCODE );
	}

	/**
	 * Is the landing page shown on the current request?
	 *
	 * @return bool
	 */
	public static function is_landing() {
		if ( null === self::$is_landing ) {
			self::$is_landing = is_singular() && ( self::uses_template() || self::has_shortcode_in( get_queried_object() ) );
			/**
			 * Filters whether the current request shows the Codexa landing page (controls asset loading).
			 *
			 * @param bool $is_landing Whether the landing page is shown.
			 */
			self::$is_landing = (bool) apply_filters( 'codexa_landing_is_landing', self::$is_landing );
		}
		return self::$is_landing;
	}

	/**
	 * First published/draft page that uses the template.
	 *
	 * @return int Page ID or 0.
	 */
	public static function find_landing_page() {
		$ids = get_posts(
			array(
				'post_type'        => 'page',
				'post_status'      => array( 'publish', 'draft', 'private' ),
				'meta_key'         => '_wp_page_template', // phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_key
				'meta_value'       => self::TEMPLATE, // phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_value
				'fields'           => 'ids',
				'posts_per_page'   => 1,
				'orderby'          => 'ID',
				'order'            => 'ASC',
				'suppress_filters' => true,
			)
		);
		return $ids ? (int) $ids[0] : 0;
	}

	/* ------------------------------------------------------------------
	 * Page template
	 * ------------------------------------------------------------------ */

	/**
	 * Offer the template in Page → Template with any theme.
	 *
	 * @param array $templates Templates.
	 * @return array
	 */
	public static function add_template( $templates ) {
		$templates[ self::TEMPLATE ] = __( 'Codexa Landing (full width)', 'codexa-landing' );
		return $templates;
	}

	/**
	 * Serve our blank-canvas template for pages that use it.
	 *
	 * @param string $template Template path chosen by WP/theme.
	 * @return string
	 */
	public static function template_include( $template ) {
		if ( self::uses_template() && ! post_password_required() ) {
			// Block themes already get these from core (block-template.php); classic themes print them in
			// header.php, which this template skips. Add only what is missing so nothing is printed twice.
			if ( false === has_action( 'wp_head', '_block_template_viewport_meta_tag' ) ) {
				add_action( 'wp_head', array( __CLASS__, 'viewport_meta' ), 0 );
			}
			if ( false === has_action( 'wp_head', '_block_template_render_title_tag' ) && ! current_theme_supports( 'title-tag' ) ) {
				add_action( 'wp_head', array( __CLASS__, 'title_tag' ), 1 );
			}
			return CODEXA_LANDING_DIR . 'templates/page-codexa-landing.php';
		}
		return $template;
	}

	/* ------------------------------------------------------------------
	 * Front end
	 * ------------------------------------------------------------------ */

	/**
	 * Viewport meta for the full-width template.
	 */
	public static function viewport_meta() {
		echo '<meta name="viewport" content="width=device-width, initial-scale=1">' . "\n";
	}

	/**
	 * <title> for the full-width template when the theme doesn't declare title-tag support.
	 */
	public static function title_tag() {
		echo '<title>' . esc_html( wp_get_document_title() ) . '</title>' . "\n";
	}

	/**
	 * Register assets and enqueue them on landing pages only.
	 */
	public static function enqueue() {
		$fonts = Codexa_Settings::get( 'fonts' );
		if ( 'local' === $fonts ) {
			wp_register_style( 'codexa-landing-fonts', self::asset( 'fonts/fonts.css' ), array(), self::ver( 'fonts/fonts.css' ) );
		} else {
			wp_register_style( 'codexa-landing-fonts', 'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap', array(), null ); // phpcs:ignore WordPress.WP.EnqueuedResourceParameters.MissingVersion
		}
		wp_register_style( 'codexa-landing', self::asset( 'css/codexa.css' ), array( 'codexa-landing-fonts' ), self::ver( 'css/codexa.css' ) );
		wp_register_script( 'codexa-landing', self::asset( 'js/codexa.js' ), array(), self::ver( 'js/codexa.js' ), true );

		if ( self::is_landing() ) {
			self::enqueue_assets();
		}
	}

	/**
	 * Enqueue the registered assets (also called late from the shortcode).
	 */
	private static function enqueue_assets() {
		wp_enqueue_style( 'codexa-landing' );
		wp_enqueue_script( 'codexa-landing' );
		if ( self::uses_template() ) {
			// Template-only reset so the theme can't add stray margins around the canvas.
			wp_add_inline_style( 'codexa-landing', 'html,body.cdx-landing-template{margin:0;padding:0;background:#fff}body.cdx-landing-template{overflow-x:hidden}' );
		}
	}

	/**
	 * Load codexa.js with defer.
	 *
	 * @param string $tag    Script tag.
	 * @param string $handle Handle.
	 * @return string
	 */
	public static function defer_script( $tag, $handle ) {
		if ( 'codexa-landing' === $handle && false === strpos( $tag, ' defer' ) ) {
			$tag = str_replace( ' src=', ' defer src=', $tag );
		}
		return $tag;
	}

	/**
	 * Preconnect to Google Fonts when it is used.
	 *
	 * @param array  $urls          URLs.
	 * @param string $relation_type Relation.
	 * @return array
	 */
	public static function resource_hints( $urls, $relation_type ) {
		if ( 'preconnect' === $relation_type && self::is_landing() && 'local' !== Codexa_Settings::get( 'fonts' ) ) {
			$urls[] = 'https://fonts.googleapis.com';
			$urls[] = array(
				'href'        => 'https://fonts.gstatic.com',
				'crossorigin' => 'anonymous',
			);
		}
		return $urls;
	}

	/**
	 * Preload the hero logo and print the meta description.
	 */
	public static function head() {
		if ( ! self::is_landing() ) {
			return;
		}
		printf( '<link rel="preload" as="image" href="%s" fetchpriority="high">' . "\n", esc_url( self::asset( 'img/codexa-logo-blue.png' ) ) );

		$seo_plugin  = defined( 'WPSEO_VERSION' ) || class_exists( 'RankMath' ) || defined( 'AIOSEO_VERSION' ) || defined( 'SEOPRESS_VERSION' );
		$description = trim( (string) Codexa_Settings::get( 'seo_description' ) );
		if ( ! $seo_plugin && '' !== $description ) {
			printf( '<meta name="description" content="%s">' . "\n", esc_attr( $description ) );
		}
	}

	/**
	 * Document title on pages that use the full-width template.
	 *
	 * @param string $title Title ('' lets WP build it).
	 * @return string
	 */
	public static function document_title( $title ) {
		if ( self::uses_template() ) {
			$custom = trim( (string) Codexa_Settings::get( 'seo_title' ) );
			if ( '' !== $custom ) {
				return $custom;
			}
		}
		return $title;
	}

	/**
	 * Markup of the landing page.
	 *
	 * @return string
	 */
	public static function render() {
		$cdx = Codexa_Settings::all();
		ob_start();
		include CODEXA_LANDING_DIR . 'templates/partials/landing.php';
		return (string) ob_get_clean();
	}

	/**
	 * [codexa_landing] shortcode.
	 *
	 * @return string
	 */
	public static function shortcode() {
		// Covers builders that store content where is_landing() can't see it (enqueued late, in the footer).
		if ( ! wp_style_is( 'codexa-landing', 'enqueued' ) ) {
			self::enqueue_assets();
		}
		return self::render();
	}

	/* ------------------------------------------------------------------
	 * Admin: setup notice + actions
	 * ------------------------------------------------------------------ */

	/**
	 * "Create page" buttons.
	 */
	public static function setup_buttons() {
		$base = admin_url( 'admin-post.php' );
		$make = wp_nonce_url( add_query_arg( array( 'action' => 'codexa_landing_setup' ), $base ), 'codexa_landing_setup' );
		$home = wp_nonce_url(
			add_query_arg(
				array(
					'action'     => 'codexa_landing_setup',
					'front_page' => 1,
				),
				$base
			),
			'codexa_landing_setup'
		);
		$page_id  = self::find_landing_page();
		$is_front = $page_id && 'page' === get_option( 'show_on_front' ) && (int) get_option( 'page_on_front' ) === $page_id;

		if ( ! $is_front ) {
			printf(
				'<a class="button button-primary" href="%1$s">%2$s</a> ',
				esc_url( $home ),
				$page_id ? esc_html__( 'Set the Codexa page as the homepage', 'codexa-landing' ) : esc_html__( 'Create the Codexa page and set it as the homepage', 'codexa-landing' )
			);
		}
		printf(
			'<a class="button" href="%1$s">%2$s</a>',
			esc_url( $make ),
			$page_id ? esc_html__( 'Create another Codexa page', 'codexa-landing' ) : esc_html__( 'Only create the page', 'codexa-landing' )
		);
	}

	/**
	 * Notice shown after activation.
	 */
	public static function admin_notice() {
		if ( ! current_user_can( 'manage_options' ) || ! get_option( self::NOTICE_FLAG ) ) {
			return;
		}
		$screen = function_exists( 'get_current_screen' ) ? get_current_screen() : null;
		if ( $screen && 'settings_page_' . Codexa_Settings::PAGE === $screen->id ) {
			return; // The settings page has the same buttons.
		}
		$dismiss = wp_nonce_url( add_query_arg( array( 'action' => 'codexa_landing_dismiss' ), admin_url( 'admin-post.php' ) ), 'codexa_landing_dismiss' );
		?>
		<div class="notice notice-info">
			<p><strong><?php esc_html_e( 'Codexa Landing is active.', 'codexa-landing' ); ?></strong>
			<?php esc_html_e( 'Create a “Codexa” page that uses the full-width landing template? You can also set it as the site’s homepage. Nothing is changed until you click a button.', 'codexa-landing' ); ?></p>
			<p>
				<?php self::setup_buttons(); ?>
				<a class="button-link" style="margin-left:8px" href="<?php echo esc_url( $dismiss ); ?>"><?php esc_html_e( 'No thanks', 'codexa-landing' ); ?></a>
			</p>
		</div>
		<?php
	}

	/**
	 * Success/error messages after a setup action.
	 */
	public static function render_status_messages() {
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- display only.
		$status = isset( $_GET['codexa_setup'] ) ? sanitize_key( wp_unslash( $_GET['codexa_setup'] ) ) : '';
		if ( 'created' === $status ) {
			echo '<div class="notice notice-success is-dismissible"><p>' . esc_html__( 'The Codexa page was created.', 'codexa-landing' ) . '</p></div>';
		} elseif ( 'front' === $status ) {
			echo '<div class="notice notice-success is-dismissible"><p>' . esc_html__( 'The Codexa page is now the homepage.', 'codexa-landing' ) . '</p></div>';
		} elseif ( 'error' === $status ) {
			echo '<div class="notice notice-error is-dismissible"><p>' . esc_html__( 'The page could not be created.', 'codexa-landing' ) . '</p></div>';
		}
	}

	/**
	 * Create the page (and optionally make it the front page).
	 */
	public static function handle_setup() {
		if ( ! current_user_can( 'manage_options' ) ) {
			wp_die( esc_html__( 'You are not allowed to do this.', 'codexa-landing' ), 403 );
		}
		check_admin_referer( 'codexa_landing_setup' );

		$front   = ! empty( $_GET['front_page'] );
		$page_id = self::find_landing_page();

		// "Only create" always creates; "set as homepage" reuses an existing page.
		if ( ! $page_id || ! $front ) {
			$page_id = wp_insert_post(
				array(
					'post_type'    => 'page',
					'post_status'  => 'publish',
					'post_title'   => 'Codexa',
					'post_content' => '',
					'meta_input'   => array( '_wp_page_template' => self::TEMPLATE ),
				),
				true
			);
		}

		$status = 'created';
		if ( is_wp_error( $page_id ) || ! $page_id ) {
			$status = 'error';
		} else {
			if ( 'publish' !== get_post_status( $page_id ) && $front ) {
				wp_update_post(
					array(
						'ID'          => $page_id,
						'post_status' => 'publish',
					)
				);
			}
			if ( $front ) {
				update_option( 'show_on_front', 'page' );
				update_option( 'page_on_front', (int) $page_id );
				$status = 'front';
			}
		}
		delete_option( self::NOTICE_FLAG );

		wp_safe_redirect( add_query_arg( 'codexa_setup', $status, admin_url( 'options-general.php?page=' . Codexa_Settings::PAGE ) ) );
		exit;
	}

	/**
	 * Dismiss the activation notice.
	 */
	public static function handle_dismiss() {
		if ( ! current_user_can( 'manage_options' ) ) {
			wp_die( esc_html__( 'You are not allowed to do this.', 'codexa-landing' ), 403 );
		}
		check_admin_referer( 'codexa_landing_dismiss' );
		delete_option( self::NOTICE_FLAG );
		wp_safe_redirect( wp_get_referer() ? wp_get_referer() : admin_url() );
		exit;
	}
}
