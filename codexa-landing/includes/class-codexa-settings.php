<?php
/**
 * Settings → Codexa Landing.
 *
 * @package CodexaLanding
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

/**
 * Stores and sanitises the plugin options (one array option).
 */
class Codexa_Settings {

	const OPTION = 'codexa_landing_settings';
	const PAGE   = 'codexa-landing';

	/**
	 * Default values.
	 *
	 * @return array
	 */
	public static function defaults() {
		return array(
			'learn_more_url'     => '',
			'learn_more_new_tab' => 0,
			'copyright'          => '© {year} Codexa',
			'pulse'              => 4,
			'liquid'             => 1,
			'fonts'              => 'google',
			'seo_title'          => 'Codexa — Coming soon',
			'seo_description'    => 'Codexa — a concern of Pandughar Group. Coming soon.',
		);
	}

	/**
	 * Saved values merged over the defaults.
	 *
	 * @return array
	 */
	public static function all() {
		$saved = get_option( self::OPTION, array() );
		return wp_parse_args( is_array( $saved ) ? $saved : array(), self::defaults() );
	}

	/**
	 * One setting.
	 *
	 * @param string $key Setting key.
	 * @return mixed
	 */
	public static function get( $key ) {
		$all = self::all();
		return isset( $all[ $key ] ) ? $all[ $key ] : null;
	}

	/**
	 * Copyright line with {year} replaced.
	 *
	 * @return string
	 */
	public static function copyright() {
		return str_replace( '{year}', wp_date( 'Y' ), (string) self::get( 'copyright' ) );
	}

	/**
	 * Hook into the admin.
	 */
	public static function init() {
		add_action( 'admin_menu', array( __CLASS__, 'menu' ) );
		add_action( 'admin_init', array( __CLASS__, 'register' ) );
		add_filter( 'plugin_action_links_' . plugin_basename( CODEXA_LANDING_FILE ), array( __CLASS__, 'action_links' ) );
	}

	/**
	 * "Settings" link on the Plugins screen.
	 *
	 * @param array $links Existing links.
	 * @return array
	 */
	public static function action_links( $links ) {
		$url = admin_url( 'options-general.php?page=' . self::PAGE );
		array_unshift( $links, '<a href="' . esc_url( $url ) . '">' . esc_html__( 'Settings', 'codexa-landing' ) . '</a>' );
		return $links;
	}

	/**
	 * Add the page under Settings.
	 */
	public static function menu() {
		add_options_page(
			__( 'Codexa Landing', 'codexa-landing' ),
			__( 'Codexa Landing', 'codexa-landing' ),
			'manage_options',
			self::PAGE,
			array( __CLASS__, 'render_page' )
		);
	}

	/**
	 * Register the option and its fields.
	 */
	public static function register() {
		register_setting(
			self::PAGE,
			self::OPTION,
			array(
				'type'              => 'array',
				'sanitize_callback' => array( __CLASS__, 'sanitize' ),
				'default'           => self::defaults(),
			)
		);

		add_settings_section( 'cdx_button', __( '“Learn more” button', 'codexa-landing' ), '__return_false', self::PAGE );
		add_settings_section( 'cdx_content', __( 'Content', 'codexa-landing' ), '__return_false', self::PAGE );
		add_settings_section( 'cdx_motion', __( 'Motion', 'codexa-landing' ), '__return_false', self::PAGE );
		add_settings_section( 'cdx_fonts', __( 'Fonts', 'codexa-landing' ), '__return_false', self::PAGE );
		add_settings_section( 'cdx_seo', __( 'Search engines', 'codexa-landing' ), array( __CLASS__, 'seo_intro' ), self::PAGE );

		$fields = array(
			'learn_more_url'     => array( __( 'Destination URL', 'codexa-landing' ), 'cdx_button' ),
			'learn_more_new_tab' => array( __( 'Open in a new tab', 'codexa-landing' ), 'cdx_button' ),
			'copyright'          => array( __( 'Copyright line', 'codexa-landing' ), 'cdx_content' ),
			'pulse'              => array( __( 'Hero pulse speed (seconds)', 'codexa-landing' ), 'cdx_motion' ),
			'liquid'             => array( __( 'Liquid strength (0–2)', 'codexa-landing' ), 'cdx_motion' ),
			'fonts'              => array( __( 'Font source', 'codexa-landing' ), 'cdx_fonts' ),
			'seo_title'          => array( __( 'Page title', 'codexa-landing' ), 'cdx_seo' ),
			'seo_description'    => array( __( 'Meta description', 'codexa-landing' ), 'cdx_seo' ),
		);
		foreach ( $fields as $key => $field ) {
			add_settings_field(
				'cdx_' . $key,
				$field[0],
				array( __CLASS__, 'field' ),
				self::PAGE,
				$field[1],
				array(
					'key'       => $key,
					'label_for' => 'cdx_' . $key,
				)
			);
		}
	}

	/**
	 * Intro text for the SEO section.
	 */
	public static function seo_intro() {
		echo '<p>' . esc_html__( 'Used on pages that show the landing page. If Yoast SEO, Rank Math, All in One SEO or SEOPress is active, the meta description is left to that plugin.', 'codexa-landing' ) . '</p>';
	}

	/**
	 * Sanitise the submitted option.
	 *
	 * @param mixed $input Raw input.
	 * @return array
	 */
	public static function sanitize( $input ) {
		$input = is_array( $input ) ? $input : array();
		$d     = self::defaults();
		$out   = array();

		$out['learn_more_url']     = isset( $input['learn_more_url'] ) ? esc_url_raw( trim( (string) $input['learn_more_url'] ) ) : '';
		$out['learn_more_new_tab'] = empty( $input['learn_more_new_tab'] ) ? 0 : 1;

		$copyright        = isset( $input['copyright'] ) ? sanitize_text_field( (string) $input['copyright'] ) : '';
		$out['copyright'] = '' === $copyright ? $d['copyright'] : $copyright;

		$pulse        = isset( $input['pulse'] ) ? (float) $input['pulse'] : $d['pulse'];
		$out['pulse'] = $pulse > 0 ? min( 30, max( 0.5, round( $pulse, 2 ) ) ) : $d['pulse'];

		$liquid        = isset( $input['liquid'] ) && is_numeric( $input['liquid'] ) ? (float) $input['liquid'] : $d['liquid'];
		$out['liquid'] = min( 2, max( 0, round( $liquid, 2 ) ) );

		$out['fonts'] = ( isset( $input['fonts'] ) && 'local' === $input['fonts'] ) ? 'local' : 'google';

		$title            = isset( $input['seo_title'] ) ? sanitize_text_field( (string) $input['seo_title'] ) : '';
		$out['seo_title'] = '' === $title ? $d['seo_title'] : $title;

		$out['seo_description'] = isset( $input['seo_description'] ) ? sanitize_textarea_field( (string) $input['seo_description'] ) : '';

		return $out;
	}

	/**
	 * Render one field.
	 *
	 * @param array $args Field args.
	 */
	public static function field( $args ) {
		$key  = $args['key'];
		$val  = self::get( $key );
		$id   = 'cdx_' . $key;
		$name = self::OPTION . '[' . $key . ']';

		switch ( $key ) {
			case 'learn_more_url':
				printf( '<input type="url" class="regular-text code" id="%1$s" name="%2$s" value="%3$s" placeholder="https://">', esc_attr( $id ), esc_attr( $name ), esc_attr( $val ) );
				echo '<p class="description">' . esc_html__( 'Leave empty to hide the button.', 'codexa-landing' ) . '</p>';
				break;

			case 'learn_more_new_tab':
				printf( '<input type="checkbox" id="%1$s" name="%2$s" value="1" %3$s>', esc_attr( $id ), esc_attr( $name ), checked( 1, (int) $val, false ) );
				break;

			case 'copyright':
				printf( '<input type="text" class="regular-text" id="%1$s" name="%2$s" value="%3$s">', esc_attr( $id ), esc_attr( $name ), esc_attr( $val ) );
				echo '<p class="description">' . esc_html__( '{year} is replaced with the current year. Shown at the top left of the coming-soon section.', 'codexa-landing' ) . '</p>';
				break;

			case 'pulse':
				printf( '<input type="number" class="small-text" id="%1$s" name="%2$s" value="%3$s" min="0.5" max="30" step="0.1">', esc_attr( $id ), esc_attr( $name ), esc_attr( $val ) );
				echo '<p class="description">' . esc_html__( 'Duration of one pulse of the orb behind the hero logo. Default 4.', 'codexa-landing' ) . '</p>';
				break;

			case 'liquid':
				printf( '<input type="number" class="small-text" id="%1$s" name="%2$s" value="%3$s" min="0" max="2" step="0.1">', esc_attr( $id ), esc_attr( $name ), esc_attr( $val ) );
				echo '<p class="description">' . esc_html__( 'How strongly the glass panes react to scroll and the mouse. 0 = off, 1 = default, 2 = strong.', 'codexa-landing' ) . '</p>';
				break;

			case 'fonts':
				$opts = array(
					'google' => __( 'Google Fonts', 'codexa-landing' ),
					'local'  => __( 'Self-hosted (from the plugin’s assets/fonts folder — no requests to Google)', 'codexa-landing' ),
				);
				echo '<fieldset>';
				foreach ( $opts as $v => $label ) {
					printf(
						'<label><input type="radio" name="%1$s" value="%2$s" %3$s> %4$s</label><br>',
						esc_attr( $name ),
						esc_attr( $v ),
						checked( $val, $v, false ),
						esc_html( $label )
					);
				}
				echo '</fieldset>';
				break;

			case 'seo_title':
				printf( '<input type="text" class="regular-text" id="%1$s" name="%2$s" value="%3$s">', esc_attr( $id ), esc_attr( $name ), esc_attr( $val ) );
				break;

			case 'seo_description':
				printf( '<textarea class="large-text" rows="2" id="%1$s" name="%2$s">%3$s</textarea>', esc_attr( $id ), esc_attr( $name ), esc_textarea( $val ) );
				echo '<p class="description">' . esc_html__( 'Leave empty to print no meta description.', 'codexa-landing' ) . '</p>';
				break;
		}
	}

	/**
	 * Render the settings screen.
	 */
	public static function render_page() {
		if ( ! current_user_can( 'manage_options' ) ) {
			return;
		}
		$page_id = Codexa_Landing::find_landing_page();
		?>
		<div class="wrap">
			<h1><?php echo esc_html( get_admin_page_title() ); ?></h1>

			<?php Codexa_Landing::render_status_messages(); ?>

			<div class="card" style="max-width:720px">
				<h2><?php esc_html_e( 'Showing the page', 'codexa-landing' ); ?></h2>
				<p><?php esc_html_e( 'Recommended: on any page, choose the template “Codexa Landing (full width)” (Page → Template). The landing page then runs edge to edge with no theme header or footer.', 'codexa-landing' ); ?></p>
				<p><?php esc_html_e( 'Alternative: put the shortcode [codexa_landing] in a page (block editor Shortcode block or Elementor Shortcode widget). It renders inside the theme’s layout, so the hero may not be full width.', 'codexa-landing' ); ?></p>
				<?php if ( $page_id ) : ?>
					<p>
						<?php
						printf(
							/* translators: %s: page title */
							esc_html__( 'Landing page: %s', 'codexa-landing' ),
							'<a href="' . esc_url( get_permalink( $page_id ) ) . '">' . esc_html( get_the_title( $page_id ) ) . '</a>'
						);
						if ( (int) get_option( 'page_on_front' ) === $page_id && 'page' === get_option( 'show_on_front' ) ) {
							echo ' — ' . esc_html__( 'set as the homepage.', 'codexa-landing' );
						}
						?>
					</p>
				<?php endif; ?>
				<p>
					<?php Codexa_Landing::setup_buttons(); ?>
				</p>
			</div>

			<form action="options.php" method="post">
				<?php
				settings_fields( self::PAGE );
				do_settings_sections( self::PAGE );
				submit_button();
				?>
			</form>
		</div>
		<?php
	}
}
